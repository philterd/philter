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

/** Deleting a policy over real HTTP: a refusal is reported, not answered with 200. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PoliciesApiIT {

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
    private ObjectId userId;
    private String key;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "policies-" + UUID.randomUUID();
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
    @DisplayName("Deleting a policy that does not exist returns 404 with a message")
    void deleteMissing() throws Exception {
        final HttpResponse<String> refused = send("DELETE", "/api/policies/missing", null);
        assertEquals(404, refused.statusCode(), refused.body());
        assertEquals("Policy does not exist.", json(refused).get("message").getAsString());
    }

    @Test
    @DisplayName("Deleting the default policy returns 409 with a reason, and the policy is kept")
    void deleteDefault() throws Exception {
        final HttpResponse<String> refused = send("DELETE", "/api/policies/default", null);
        assertEquals(409, refused.statusCode(), refused.body());
        assertEquals("Cannot delete the default policy.", json(refused).get("message").getAsString());
        assertEquals("policy_default", json(refused).get("reason").getAsString());
        assertEquals(200, send("GET", "/api/policies/default", null).statusCode(), "the default policy is kept");
    }

    @Test
    @DisplayName("Deleting a policy returns 200 and the policy is gone")
    void deleteSucceeds() throws Exception {
        assertEquals(201, send("POST", "/api/policies?name=temporary", POLICY).statusCode());

        final HttpResponse<String> deleted = send("DELETE", "/api/policies/temporary", null);
        assertEquals(200, deleted.statusCode(), deleted.body());
        assertEquals(404, send("GET", "/api/policies/temporary", null).statusCode());
    }

}
