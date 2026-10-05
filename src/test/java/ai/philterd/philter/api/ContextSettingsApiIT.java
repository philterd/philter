/*
 *     Copyright 2026 Philterd, LLC @ https://www.philterd.ai
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.philterd.philter.api;

import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context's settings over the API: readable, changed one at a time without resetting the other, a
 * request that changes nothing refused, and each change audited by the settings it changed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ContextSettingsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired
    private Environment environment;

    @Autowired
    private UserService userService;

    @Autowired
    private ApiKeyDataService apiKeyDataService;

    @Autowired
    private PolicyDataService policyDataService;

    @Autowired
    private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String username;
    private String key;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        username = "ctx-" + UUID.randomUUID();
        userService.createUser("req", username, "user", policyDataService, contextDataService, "test");
        key = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test", ApiKeyScope.all()).getMessage();
        final String adminName = "ctx-admin-" + UUID.randomUUID();
        userService.createUser("req", adminName, "admin", policyDataService, contextDataService, "test");
        adminKey = apiKeyDataService.createApiKey("req", userService.findByUsername(adminName).getId(), "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject read(final String name, final String apiKey, final String query) throws Exception {
        final HttpResponse<String> response = send("GET", "/api/contexts/" + name + query, apiKey);
        assertEquals(200, response.statusCode(), response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private String create(final boolean disambiguation, final boolean ledger) throws Exception {
        final String name = "settings-" + UUID.randomUUID();
        assertEquals(200, send("POST", "/api/contexts?name=" + name + "&entity_type_disambiguation=" + disambiguation
                + "&ledger=" + ledger, key).statusCode());
        return name;
    }

    private List<String> auditDetails(final String contextName) throws Exception {
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=500&event=context_updated", adminKey);
        assertEquals(200, audit.statusCode(), audit.body());
        final List<String> details = new ArrayList<>();
        for (final JsonElement element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final String detail = element.getAsJsonObject().get("details").getAsString();
            if (detail.contains("context: " + contextName + ",")) {
                details.add(detail);
            }
        }
        return details;
    }

    @Test
    @DisplayName("A context's settings are returned with its counts, including to an administrator with owner")
    void readsBothSettings() throws Exception {
        final String name = create(true, false);
        final JsonObject context = read(name, key, "");
        assertTrue(context.get("entityTypeDisambiguation").getAsBoolean());
        assertFalse(context.get("ledger").getAsBoolean());
        assertTrue(context.has("size") && context.has("filterTypes") && context.has("untyped"));

        AdminAccessConfig.setOverrideForTesting(true);
        final JsonObject asAdmin = read(name, adminKey, "?owner=" + username);
        assertTrue(asAdmin.get("entityTypeDisambiguation").getAsBoolean());
        assertFalse(asAdmin.get("ledger").getAsBoolean());
    }

    @Test
    @DisplayName("Changing one setting leaves the other as it was, in both directions, and only the change is audited")
    void changesOnlyTheSettingGiven() throws Exception {

        final String name = create(false, true);

        assertEquals(200, send("PUT", "/api/contexts/" + name + "?entity_type_disambiguation=true", key).statusCode());
        JsonObject context = read(name, key, "");
        assertTrue(context.get("entityTypeDisambiguation").getAsBoolean());
        assertTrue(context.get("ledger").getAsBoolean(), "turning disambiguation on leaves the ledger on");

        assertEquals(200, send("PUT", "/api/contexts/" + name + "?ledger=false", key).statusCode());
        context = read(name, key, "");
        assertTrue(context.get("entityTypeDisambiguation").getAsBoolean(), "turning the ledger off leaves disambiguation on");
        assertFalse(context.get("ledger").getAsBoolean());

        // Sending a setting at its current value is not a change.
        assertEquals(200, send("PUT", "/api/contexts/" + name + "?ledger=false&entity_type_disambiguation=true", key).statusCode());

        final List<String> details = auditDetails(name);
        assertEquals(2, details.size(), "one event per change: " + details);
        assertTrue(details.stream().anyMatch(d -> d.contains("entity_type_disambiguation: true") && !d.contains("ledger:")), details.toString());
        assertTrue(details.stream().anyMatch(d -> d.contains("ledger: false") && !d.contains("entity_type_disambiguation")), details.toString());

    }

    @Test
    @DisplayName("A request with neither setting is refused and changes nothing")
    void refusesARequestWithNeitherSetting() throws Exception {
        final String name = create(true, true);
        final HttpResponse<String> response = send("PUT", "/api/contexts/" + name, key);
        assertEquals(400, response.statusCode(), response.body());
        final JsonObject context = read(name, key, "");
        assertTrue(context.get("entityTypeDisambiguation").getAsBoolean());
        assertTrue(context.get("ledger").getAsBoolean());
        assertTrue(auditDetails(name).isEmpty());
    }

}
