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
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An administrator reaches a deactivated user's retained resources with owner, as for an active user. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class DeactivatedOwnerApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String POLICY = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String adminKey;
    private String leaver;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        adminKey = keyFor(seedUser("admin"));
        leaver = seedUser("user");

        // As in the issue: a hold and a policy for the user, set while they were active.
        assertEquals(201, send(adminKey, "POST", "/api/holds?owner=" + leaver,
                "{\"reference\":\"case-7\",\"scopeType\":\"user\"}").statusCode());
        assertEquals(201, send(adminKey, "POST", "/api/policies?name=kept&owner=" + leaver, POLICY).statusCode());
        assertEquals(200, send(adminKey, "POST", "/api/users/" + leaver + "/deactivate", null).statusCode());
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser(final String role) {
        final String username = "leaver-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return username;
    }

    private String keyFor(final String username) {
        return apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> send(final String key, final String method, final String path, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("An administrator reads a deactivated user's resources and releases their hold")
    void readsAndReleases() throws Exception {

        final String owner = "owner=" + leaver;
        assertEquals(200, send(adminKey, "GET", "/api/policies/kept?" + owner, null).statusCode());
        assertEquals(200, send(adminKey, "GET", "/api/policies?" + owner, null).statusCode());
        assertEquals(200, send(adminKey, "GET", "/api/contexts?" + owner, null).statusCode());
        assertEquals(200, send(adminKey, "GET", "/api/lists?" + owner, null).statusCode());
        assertEquals(200, send(adminKey, "GET", "/api/ledger?" + owner, null).statusCode());
        assertEquals(200, send(adminKey, "GET", "/api/holds/case-7?" + owner, null).statusCode());

        final HttpResponse<String> released = send(adminKey, "DELETE", "/api/holds/case-7?" + owner, null);
        assertEquals(200, released.statusCode(), released.body());
        assertEquals(404, send(adminKey, "GET", "/api/holds/case-7?" + owner, null).statusCode(), "the hold is gone");

    }

    @Test
    @DisplayName("Writes for a deactivated user are allowed, as for an active one")
    void writesAreAllowed() throws Exception {
        assertEquals(201, send(adminKey, "POST", "/api/policies?name=after&owner=" + leaver, POLICY).statusCode());
        assertEquals(201, send(adminKey, "POST", "/api/holds?owner=" + leaver,
                "{\"reference\":\"case-8\",\"scopeType\":\"user\"}").statusCode());
    }

    @Test
    @DisplayName("A non-administrator still gets 404 for a deactivated owner, and an unknown username is 404")
    void refusals() throws Exception {
        final String userKey = keyFor(seedUser("user"));
        assertEquals(404, send(userKey, "GET", "/api/policies/kept?owner=" + leaver, null).statusCode());
        assertEquals(404, send(userKey, "DELETE", "/api/holds/case-7?owner=" + leaver, null).statusCode());
        assertEquals(404, send(adminKey, "GET", "/api/policies?owner=never-existed-" + UUID.randomUUID(), null).statusCode());

        AdminAccessConfig.setOverrideForTesting(false);
        assertEquals(404, send(adminKey, "GET", "/api/policies/kept?owner=" + leaver, null).statusCode(),
                "the kill switch still applies");
    }

    @Test
    @DisplayName("Reaching a deactivated user is audited as cross-user access")
    void audited() throws Exception {
        assertEquals(200, send(adminKey, "DELETE", "/api/holds/case-7?owner=" + leaver, null).statusCode());

        final HttpResponse<String> audit = send(adminKey, "GET", "/api/audit?event=admin_cross_user_access&limit=100", null);
        assertEquals(200, audit.statusCode(), audit.body());
        final Set<String> details = new HashSet<>();
        for (final JsonElement event : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject object = event.getAsJsonObject();
            if (object.has("details")) {
                details.add(object.get("details").getAsString());
            }
        }
        assertTrue(details.stream().anyMatch(d -> d.contains("release legal hold 'case-7'")), details.toString());
    }

}
