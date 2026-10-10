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

import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listings end to end against a real query engine: totals that count what matches, search that ignores
 * case and treats its input as text, sorting in both directions, and the users filters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ListingsIT {

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
    private String key;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        key = keyFor(seed("listings-" + UUID.randomUUID(), "user"));
        adminKey = keyFor(seed("listings-admin-" + UUID.randomUUID(), "admin"));
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private ObjectId seed(final String username, final String role) {
        assertTrue(userService.createUser("req", username, role, policyDataService, contextDataService, "test").isSuccessful());
        return userService.findByUsername(username).getId();
    }

    private String keyFor(final ObjectId userId) {
        return apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> send(final String apiKey, final String method, final String path, final String body)
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

    private JsonObject list(final String apiKey, final String path) throws Exception {
        final HttpResponse<String> response = send(apiKey, "GET", path, null);
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private static List<String> strings(final JsonArray array) {
        final List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.isJsonObject()
                ? value.getAsJsonObject().get("name").getAsString() : value.getAsString()));
        return values;
    }

    @Test
    @DisplayName("Policies page with a total, search ignoring case, and sort both ways")
    void policies() throws Exception {
        for (final String name : List.of("claims-a", "Claims-b", "claims-c", "other")) {
            assertEquals(201, send(key, "POST", "/api/policies?name=" + name, POLICY).statusCode());
        }

        final JsonObject all = list(key, "/api/policies?limit=2");
        assertEquals(5, all.get("total").getAsInt(), "four created plus the default policy");
        assertEquals(2, all.getAsJsonArray("policies").size());

        final JsonObject claims = list(key, "/api/policies?q=CLAIMS");
        assertEquals(3, claims.get("total").getAsInt(), "the search ignores case");
        assertEquals(List.of("Claims-b", "claims-a", "claims-c"), strings(claims.getAsJsonArray("policies")),
                "names sort as stored, upper case first");

        assertEquals(List.of("claims-c", "claims-a", "Claims-b"),
                strings(list(key, "/api/policies?q=claims&order=desc").getAsJsonArray("policies")));

        final JsonObject newest = list(key, "/api/policies?sort=created&order=desc&limit=1");
        assertEquals(List.of("other"), strings(newest.getAsJsonArray("policies")));

        assertEquals(400, send(key, "GET", "/api/policies?sort=size", null).statusCode());
        assertEquals(400, send(key, "GET", "/api/policies?order=sideways", null).statusCode());
    }

    @Test
    @DisplayName("A search is matched as text, so pattern characters find only themselves")
    void searchIsText() throws Exception {
        assertEquals(201, send(key, "POST", "/api/policies?name=a-b", POLICY).statusCode());
        assertEquals(201, send(key, "POST", "/api/policies?name=axb", POLICY).statusCode());

        final String dot = URLEncoder.encode(".", StandardCharsets.UTF_8);
        assertEquals(0, list(key, "/api/policies?q=" + dot).get("total").getAsInt(), "a dot is not a wildcard");
        assertEquals(1, list(key, "/api/policies?q=a-").get("total").getAsInt());
    }

    @Test
    @DisplayName("A user's own custom lists are paged with a total")
    void customListsArePaged() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(201, send(key, "POST", "/api/lists/list-" + i, "[\"item\"]").statusCode());
        }

        final JsonObject first = list(key, "/api/lists?limit=2");
        assertEquals(3, first.get("total").getAsInt());
        assertEquals(List.of("list-0", "list-1"), strings(first.getAsJsonArray("lists")));
        assertEquals(List.of("list-2"), strings(list(key, "/api/lists?limit=2&offset=2").getAsJsonArray("lists")));
    }

    @Test
    @DisplayName("Users can be searched and filtered by role and active state")
    void usersFilters() throws Exception {
        final String marker = "find-me-" + UUID.randomUUID();
        seed(marker + "-a", "admin");
        final ObjectId plain = seed(marker + "-b", "user");
        final ObjectId gone = seed(marker + "-c", "user");
        assertTrue(userService.deactivateUser("req", userService.findOneById(gone), "test").isSuccessful());

        assertEquals(3, list(adminKey, "/api/users?q=" + marker).get("total").getAsInt());
        assertEquals(1, list(adminKey, "/api/users?q=" + marker + "&role=admin").get("total").getAsInt());
        final JsonObject activeUsers = list(adminKey, "/api/users?q=" + marker + "&role=user&active=true");
        assertEquals(1, activeUsers.get("total").getAsInt());
        assertEquals(plain.toHexString(), activeUsers.getAsJsonArray("users").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1, list(adminKey, "/api/users?q=" + marker + "&active=false").get("total").getAsInt());
    }

}
