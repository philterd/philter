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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every /api response returns its request's id, and the audit events the request causes carry the same id. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class RequestIdsIT {

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

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private String keyFor(final String role) {
        final String username = "request-ids-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, role, policyDataService, contextDataService, "test").isSuccessful());
        final ObjectId userId = userService.findByUsername(username).getId();
        return apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> send(final String method, final String path, final String key, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (key != null) {
            builder.header("Authorization", "Bearer " + key);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String requestId(final HttpResponse<String> response) {
        return response.headers().firstValue("X-Request-Id").orElseThrow(
                () -> new AssertionError("no X-Request-Id on a " + response.statusCode() + ": " + response.uri()));
    }

    @Test
    @DisplayName("Every kind of /api response returns a request id, a different one each time")
    void everyResponseHasAnId() throws Exception {
        final String key = keyFor("user");

        final Set<String> ids = new HashSet<>();
        ids.add(requestId(send("GET", "/api/health", null, null)));
        ids.add(requestId(send("GET", "/api/policies", null, null)));
        ids.add(requestId(send("GET", "/api/policies", "sk_" + "a".repeat(32), null)));
        ids.add(requestId(send("GET", "/api/no-such-endpoint", key, null)));
        ids.add(requestId(send("GET", "/api/policies?sort=size", key, null)));
        ids.add(requestId(send("GET", "/api/policies", key, null)));

        assertEquals(6, ids.size(), "each request gets its own id: " + ids);
    }

    @Test
    @DisplayName("The audit event a request causes carries the id the response returned")
    void auditEventsCarryTheResponsesId() throws Exception {
        final String key = keyFor("user");
        final String adminKey = keyFor("admin");

        final String reference = "REF-" + UUID.randomUUID();
        final HttpResponse<String> set = send("POST", "/api/holds", key,
                "{\"reference\":\"" + reference + "\",\"scopeType\":\"user\",\"reason\":\"test\"}");
        assertEquals(201, set.statusCode(), set.body());
        final String id = requestId(set);

        boolean found = false;
        final JsonObject page = gson.fromJson(send("GET", "/api/audit?event=legal_hold_set&limit=100", adminKey, null).body(),
                JsonObject.class);
        for (final JsonElement event : page.getAsJsonArray("events")) {
            if (id.equals(event.getAsJsonObject().get("requestId").getAsString())) {
                found = true;
            }
        }
        assertTrue(found, "the legal_hold_set event carries the request's id " + id + ": " + page);
    }

}
