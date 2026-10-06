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
import com.google.gson.JsonArray;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A custom list's description and size, read and written over real HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class CustomListsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String username;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        username = seedUser("user");
        key = seedKey(username);
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private String seedUser(final String role) {
        final String name = "lists-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", name, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return name;
    }

    private String seedKey(final String name) {
        return apiKeyDataService.createApiKey("req", userService.findByUsername(name).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private JsonObject getList(final String path, final String apiKey) throws Exception {
        final HttpResponse<String> response = send("GET", path, apiKey, null);
        assertEquals(200, response.statusCode(), response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    /** The named list's entry in a listing, failing if it is not there. */
    private static JsonObject entry(final JsonArray listing, final String name) {
        for (final JsonElement element : listing) {
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError(name + " is not listed: " + listing);
    }

    @Test
    @DisplayName("A list's description is returned with its items, and listed with its size")
    void readsTheDescription() throws Exception {

        assertEquals(201, send("POST", "/api/lists/projects?description=" + encode("Code names"), key,
                "[\"Project Cardinal\",\"Project Heron\"]").statusCode());

        final JsonObject list = getList("/api/lists/projects", key);
        assertEquals("Code names", list.get("description").getAsString());
        assertEquals(2, list.getAsJsonArray("lists").size());

        final HttpResponse<String> listed = send("GET", "/api/lists", key, null);
        assertEquals(200, listed.statusCode(), listed.body());
        final JsonObject summary = entry(gson.fromJson(listed.body(), JsonArray.class), "projects");
        assertEquals("Code names", summary.get("description").getAsString());
        assertEquals(2, summary.get("size").getAsInt());
        assertFalse(summary.has("owner"), "a per-user listing names no owner");
        assertFalse(summary.has("lists"), "a listing does not carry the items");

    }

    @Test
    @DisplayName("A list created without a description has an empty one")
    void defaultsToAnEmptyDescription() throws Exception {
        assertEquals(201, send("POST", "/api/lists/plain", key, "[\"one\"]").statusCode());
        assertEquals("", getList("/api/lists/plain", key).get("description").getAsString());
    }

    @Test
    @DisplayName("A save without a description keeps it; an empty description clears it")
    void keepsOrClearsTheDescription() throws Exception {

        assertEquals(201, send("POST", "/api/lists/terms?description=" + encode("Internal terms"), key, "[\"one\"]").statusCode());

        assertEquals(200, send("POST", "/api/lists/terms", key, "[\"two\",\"three\"]").statusCode());
        JsonObject list = getList("/api/lists/terms", key);
        assertEquals("Internal terms", list.get("description").getAsString(), "left out, the description is kept");
        assertEquals(2, list.getAsJsonArray("lists").size(), "the items are still replaced");

        assertEquals(200, send("POST", "/api/lists/terms?description=", key, "[\"two\"]").statusCode());
        list = getList("/api/lists/terms", key);
        assertEquals("", list.get("description").getAsString(), "an empty description clears it");

        assertEquals(200, send("POST", "/api/lists/terms?description=" + encode("Renamed"), key, "[\"two\"]").statusCode());
        assertEquals("Renamed", getList("/api/lists/terms", key).get("description").getAsString());

    }

    @Test
    @DisplayName("An administrator reads another user's description with owner and in the all_users listing")
    void administratorReadsTheDescription() throws Exception {

        AdminAccessConfig.setOverrideForTesting(true);
        final String adminKey = seedKey(seedUser("admin"));
        assertEquals(201, send("POST", "/api/lists/owned?description=" + encode("Belongs to a user"), key, "[\"a\",\"b\",\"c\"]").statusCode());

        assertEquals("Belongs to a user",
                getList("/api/lists/owned?owner=" + encode(username), adminKey).get("description").getAsString());

        final HttpResponse<String> owned = send("GET", "/api/lists?owner=" + encode(username), adminKey, null);
        assertEquals(200, owned.statusCode(), owned.body());
        assertEquals(3, entry(gson.fromJson(owned.body(), JsonArray.class), "owned").get("size").getAsInt());

        JsonObject summary = null;
        for (int offset = 0; summary == null; offset += 100) {
            final HttpResponse<String> page = send("GET", "/api/lists?all_users=true&limit=100&offset=" + offset, adminKey, null);
            assertEquals(200, page.statusCode(), page.body());
            final JsonArray items = gson.fromJson(page.body(), JsonArray.class);
            for (final JsonElement element : items) {
                final JsonObject item = element.getAsJsonObject();
                if (item.get("name").getAsString().equals("owned") && item.get("owner").getAsString().equals(username)) {
                    summary = item;
                }
            }
            if (items.size() < 100) {
                break;
            }
        }
        assertNotNull(summary, "the list is in the all_users listing");
        assertEquals("Belongs to a user", summary.get("description").getAsString());
        assertEquals(3, summary.get("size").getAsInt());

    }

}
