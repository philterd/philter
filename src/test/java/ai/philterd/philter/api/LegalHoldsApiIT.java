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
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legal hold refusals over real HTTP: each carries a message, and each 409 a reason. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class LegalHoldsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String HOLD = "{\"reference\":\"LIT-1\",\"scopeType\":\"user\",\"scopeValue\":\"all\"}";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private MongoClient mongoClient;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private ObjectId userId;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "holds-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        userId = userService.findByUsername(username).getId();
        key = apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpResponse<String> send(final String method, final String path, final String body) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject json(final HttpResponse<String> response) {
        return gson.fromJson(response.body(), JsonObject.class);
    }

    @Test
    @DisplayName("Setting a hold whose reference exists is refused with hold_exists")
    void duplicateReference() throws Exception {
        assertEquals(201, send("POST", "/api/holds", HOLD).statusCode());

        final HttpResponse<String> refused = send("POST", "/api/holds", HOLD);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("hold_exists", json(refused).get("reason").getAsString());
        assertTrue(json(refused).get("message").getAsString().contains("LIT-1"), refused.body());
    }

    @Test
    @DisplayName("A hold change while an evidence operation is held is refused with operation_in_progress")
    void operationInProgress() throws Exception {
        assertEquals(201, send("POST", "/api/holds", HOLD).statusCode());

        // The guard as an interrupted operation leaves it: held until an operator recovers it.
        mongoClient.getDatabase("philter").getCollection("evidence_operation_guards").updateOne(
                Filters.eq("_id", userId),
                new Document("$set", new Document("token", "stuck").append("operation", "delete_all_by_user")),
                new UpdateOptions().upsert(true));

        final HttpResponse<String> set = send("POST", "/api/holds",
                "{\"reference\":\"LIT-2\",\"scopeType\":\"user\",\"scopeValue\":\"all\"}");
        assertEquals(409, set.statusCode(), set.body());
        assertEquals("operation_in_progress", json(set).get("reason").getAsString());
        assertTrue(json(set).has("message"), set.body());

        final HttpResponse<String> released = send("DELETE", "/api/holds/LIT-1", null);
        assertEquals(409, released.statusCode(), released.body());
        assertEquals("operation_in_progress", json(released).get("reason").getAsString());
        assertTrue(json(released).has("message"), released.body());
    }

    @Test
    @DisplayName("Releasing a hold that does not exist returns a message")
    void releaseMissing() throws Exception {
        final HttpResponse<String> refused = send("DELETE", "/api/holds/MISSING", null);
        assertEquals(404, refused.statusCode(), refused.body());
        assertEquals("Hold 'MISSING' not found.", json(refused).get("message").getAsString());
    }

}
