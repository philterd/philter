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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every refusal under /api is a JSON object with a message and a reason a client can act on, whatever
 * refused it: the authentication filter, the scope check, or a handler.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ErrorsIT {

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

    private ObjectId seed(final String role) {
        final String username = "errors-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, role, policyDataService, contextDataService, "test").isSuccessful());
        return userService.findByUsername(username).getId();
    }

    private String keyFor(final ObjectId userId, final Set<String> scopes) {
        return apiKeyDataService.createApiKey("req", userId, "test", scopes).getMessage();
    }

    private HttpResponse<String> send(final String method, final String path, final String key) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (key != null) {
            builder.header("Authorization", "Bearer " + key);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Asserts the refusal's status and reason, and that it is Philter's error shape and nothing else. */
    private JsonObject assertRefused(final HttpResponse<String> response, final int status, final String reason) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                "an error is JSON: " + response.headers().firstValue("Content-Type").orElse(""));
        final JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(body.has("message") && !body.get("message").getAsString().isBlank(), response.body());
        assertEquals(reason, body.get("reason").getAsString(), response.body());
        assertFalse(body.has("error"), "the old {error, message} shape is gone: " + response.body());
        return body;
    }

    @Test
    @DisplayName("Authentication refusals say why")
    void authentication() throws Exception {
        assertRefused(send("GET", "/api/policies", null), 401, "invalid_credentials");
        assertRefused(send("GET", "/api/policies", "not-a-key"), 401, "invalid_credentials");
        assertRefused(send("GET", "/api/policies", "sk_" + "a".repeat(32)), 401, "invalid_credentials");

        // An ended session is told to sign in again; a revoked long-lived key reads as unknown.
        final ObjectId owner = seed("user");
        final String session = apiKeyDataService.createSessionKey("req", owner, ApiKeyScope.all(), "test", null).getApiKey();
        assertEquals(204, send("DELETE", "/api/api-keys/current", session).statusCode());
        assertRefused(send("GET", "/api/policies", session), 401, "session_expired");
        final String longLived = keyFor(owner, ApiKeyScope.all());
        final String other = keyFor(owner, ApiKeyScope.all());
        final String longLivedId = apiKeyDataService.findOneByApiKey(longLived).getId().toHexString();
        assertEquals(204, send("DELETE", "/api/api-keys/" + longLivedId, other).statusCode());
        assertRefused(send("GET", "/api/policies", longLived), 401, "invalid_credentials");

        final ObjectId gone = seed("user");
        final String key = keyFor(gone, ApiKeyScope.all());
        assertTrue(userService.deactivateUser("req", userService.findOneById(gone), "test").isSuccessful());
        assertRefused(send("GET", "/api/policies", key), 401, "user_deactivated");
    }

    @Test
    @DisplayName("Authorization refusals say why")
    void authorization() throws Exception {
        final String narrow = keyFor(seed("user"), Set.of(ApiKeyScope.REDACT.getScope()));
        assertRefused(send("GET", "/api/policies", narrow), 403, "missing_scope");

        final String user = keyFor(seed("user"), ApiKeyScope.all());
        assertRefused(send("GET", "/api/users", user), 403, "admin_required");
        assertRefused(send("GET", "/api/audit/export?from=2026-01-01&to=2026-01-02", user), 403, "admin_required");
    }

    @Test
    @DisplayName("Not found, invalid requests, and conflicts carry their reasons, and a field when one is invalid")
    void handlerRefusals() throws Exception {
        final ObjectId userId = seed("user");
        final String key = keyFor(userId, ApiKeyScope.all());

        assertRefused(send("GET", "/api/policies/missing", key), 404, "not_found");
        assertRefused(send("GET", "/api/nope", key), 404, "not_found");
        assertRefused(send("GET", "/api/webhook/deliveries?owner=nobody-" + UUID.randomUUID(), key), 404, "not_found");

        final JsonObject invalid = assertRefused(send("GET", "/api/policies?sort=size", key), 400, "invalid_request");
        assertEquals("sort", invalid.get("field").getAsString());

        final String keyId = apiKeyDataService.findOneByApiKey(key).getId().toHexString();
        assertRefused(send("DELETE", "/api/api-keys/" + keyId, key), 409, "self_action_refused");
        assertRefused(send("POST", "/api/webhook/test", key), 409, "webhook_not_set");
    }

}
