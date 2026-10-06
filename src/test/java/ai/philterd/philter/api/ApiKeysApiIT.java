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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Managing keys over real HTTP, where the authentication filter and its cache are in the path: a
 * revoked key must fail on the very next request, not after the cache expires.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ApiKeysApiIT {

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

    private ObjectId seedUser(final String role) {
        final String username = "keys-" + role + "-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, null, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return userService.findByUsername(username).getId();
    }

    private String seedKey(final ObjectId userId, final Set<String> scopes) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        return response.getMessage();
    }

    private String idOf(final String apiKey) {
        return apiKeyDataService.findOneByApiKey(apiKey).getId().toHexString();
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

    @Test
    @DisplayName("A user rotates its own key without an administrator, and the old key stops at once")
    void rotatesAKey() throws Exception {

        final ObjectId user = seedUser("user");
        final String oldKey = seedKey(user, Set.of("api-keys:read", "api-keys:write", "policies:read"));

        // Warm the cache with the old key, so the revocation below must evict it to take effect.
        assertEquals(200, send("GET", "/api/policies", oldKey, null).statusCode());

        final HttpResponse<String> created = send("POST", "/api/api-keys", oldKey,
                "{\"scopes\":[\"api-keys:read\",\"api-keys:write\",\"policies:read\"]}");
        assertEquals(201, created.statusCode(), created.body());
        final JsonObject createdJson = gson.fromJson(created.body(), JsonObject.class);
        final String newKey = createdJson.get("apiKey").getAsString();
        assertEquals(idOf(newKey), createdJson.get("id").getAsString());

        final HttpResponse<String> listed = send("GET", "/api/api-keys", newKey, null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(2, gson.fromJson(listed.body(), JsonObject.class).get("total").getAsLong());
        assertFalse(listed.body().contains(newKey) || listed.body().contains(oldKey), "no key value is listed");

        assertEquals(204, send("DELETE", "/api/api-keys/" + idOf(oldKey), newKey, null).statusCode());
        assertEquals(401, send("GET", "/api/policies", oldKey, null).statusCode(), "revocation applies to the next request");
        assertEquals(200, send("GET", "/api/policies", newKey, null).statusCode());

    }

    @Test
    @DisplayName("A key cannot revoke itself, and stays usable")
    void aKeyCannotRevokeItself() throws Exception {
        final String key = seedKey(seedUser("user"), Set.of("api-keys:write", "policies:read"));

        assertEquals(409, send("DELETE", "/api/api-keys/" + idOf(key), key, null).statusCode());
        assertEquals(200, send("GET", "/api/policies", key, null).statusCode());
    }

    @Test
    @DisplayName("Narrowing a key applies to its next request")
    void narrowingAppliesAtOnce() throws Exception {
        final ObjectId user = seedUser("user");
        final String manager = seedKey(user, Set.of("api-keys:write", "policies:read", "ledger:read"));
        final String integration = seedKey(user, Set.of("policies:read", "ledger:read"));
        assertEquals(200, send("GET", "/api/ledger", integration, null).statusCode());

        final HttpResponse<String> changed = send("PUT", "/api/api-keys/" + idOf(integration) + "/scopes", manager,
                "{\"scopes\":[\"policies:read\"]}");
        assertEquals(200, changed.statusCode(), changed.body());

        assertEquals(403, send("GET", "/api/ledger", integration, null).statusCode());
        assertEquals(200, send("GET", "/api/policies", integration, null).statusCode());
    }

    @Test
    @DisplayName("A user cannot see or touch another user's keys; an administrator can")
    void otherUsersKeys() throws Exception {
        final String mine = seedKey(seedUser("user"), ApiKeyScope.all());
        final ObjectId otherUser = seedUser("user");
        final String theirs = seedKey(otherUser, Set.of("redact"));
        final String otherUsername = userService.findOneById(otherUser).getUsername();

        assertEquals(404, send("DELETE", "/api/api-keys/" + idOf(theirs), mine, null).statusCode());
        assertEquals(403, send("GET", "/api/users/" + otherUsername + "/api-keys", mine, null).statusCode());
        assertTrue(apiKeyDataService.findOneByApiKey(theirs) != null, "the refused revocation must not have happened");

        final String admin = seedKey(seedUser("admin"), ApiKeyScope.all());
        final HttpResponse<String> listed = send("GET", "/api/users/" + otherUsername + "/api-keys", admin, null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(listed.body().contains(idOf(theirs)), listed.body());

        assertEquals(204, send("DELETE", "/api/api-keys/" + idOf(theirs), admin, null).statusCode());
    }

    @Test
    @DisplayName("Every change is readable through the audit API, naming the calling key")
    void changesAreAudited() throws Exception {
        final ObjectId user = seedUser("user");
        final String manager = seedKey(user, Set.of("api-keys:write", "policies:read"));
        final String managerId = idOf(manager);
        final String target = seedKey(user, Set.of("policies:read"));
        final String targetId = idOf(target);

        final HttpResponse<String> created = send("POST", "/api/api-keys", manager, "{\"scopes\":[\"policies:read\"]}");
        final String createdId = gson.fromJson(created.body(), JsonObject.class).get("id").getAsString();
        assertEquals(200, send("PUT", "/api/api-keys/" + targetId + "/scopes", manager, "{\"scopes\":[\"policies:read\"]}").statusCode());
        assertEquals(204, send("DELETE", "/api/api-keys/" + targetId, manager, null).statusCode());

        final String admin = seedKey(seedUser("admin"), ApiKeyScope.all());
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=100", admin, null);
        assertEquals(200, audit.statusCode(), audit.body());

        final Set<String> recorded = new HashSet<>();
        for (final var element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject event = element.getAsJsonObject();
            final String details = event.has("details") ? event.get("details").getAsString() : "";
            final String principal = event.has("apiKeyId") ? event.get("apiKeyId").getAsString() : "";
            if (details.contains("api_key: " + managerId) && (principal.equals(targetId) || principal.equals(createdId))) {
                recorded.add(event.get("event").getAsString());
            }
        }
        assertTrue(recorded.containsAll(Set.of("api_key_created", "api_key_scopes_changed", "api_key_deleted")),
                "every change names the calling key: " + recorded);
    }

    @Test
    @DisplayName("Every scope is listed, in declaration order with its description, to any key")
    void listsEveryScope() throws Exception {

        // A key with no API key scopes at all can still read the list.
        final String key = seedKey(seedUser("user"), Set.of(ai.philterd.philter.model.ApiKeyScope.REDACT.getScope()));
        final HttpResponse<String> response = send("GET", "/api/api-keys/scopes", key, null);
        assertEquals(200, response.statusCode(), response.body());

        final com.google.gson.JsonArray listed = gson.fromJson(response.body(), com.google.gson.JsonObject.class).getAsJsonArray("scopes");
        final ai.philterd.philter.model.ApiKeyScope[] declared = ai.philterd.philter.model.ApiKeyScope.values();
        assertEquals(declared.length, listed.size(), "every scope, and only scopes");
        for (int i = 0; i < declared.length; i++) {
            final com.google.gson.JsonObject scope = listed.get(i).getAsJsonObject();
            assertEquals(declared[i].getScope(), scope.get("name").getAsString());
            assertEquals(declared[i].getDescription(), scope.get("description").getAsString());
        }

        assertEquals(401, httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/api-keys/scopes")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "but not without a key");

    }

    @Test
    @DisplayName("session filters the listing between session and long-lived keys, and total counts only those listed")
    void filtersBySession() throws Exception {
        final ObjectId user = seedUser("user");
        final String longLived = seedKey(user, Set.of("api-keys:read", "api-keys:write"));
        seedKey(user, Set.of("redact"));
        final String sessionId = apiKeyDataService.createSessionKey("req", user, ApiKeyScope.all(), "test", null)
                .getId().toHexString();

        final JsonObject all = gson.fromJson(send("GET", "/api/api-keys", longLived, null).body(), JsonObject.class);
        assertEquals(3, all.get("total").getAsLong());

        final JsonObject sessions = gson.fromJson(send("GET", "/api/api-keys?session=true", longLived, null).body(), JsonObject.class);
        assertEquals(1, sessions.get("total").getAsLong());
        assertEquals(sessionId, sessions.getAsJsonArray("apiKeys").get(0).getAsJsonObject().get("id").getAsString());

        final JsonObject keys = gson.fromJson(send("GET", "/api/api-keys?session=false", longLived, null).body(), JsonObject.class);
        assertEquals(2, keys.get("total").getAsLong());
        assertEquals(2, keys.getAsJsonArray("apiKeys").size());
        assertFalse(keys.toString().contains(sessionId), keys.toString());

        // The administrator listing filters the same way.
        final String admin = seedKey(seedUser("admin"), ApiKeyScope.all());
        final String username = userService.findOneById(user).getUsername();
        final JsonObject adminSessions = gson.fromJson(
                send("GET", "/api/users/" + username + "/api-keys?session=true", admin, null).body(), JsonObject.class);
        assertEquals(1, adminSessions.get("total").getAsLong());
    }

    @Test
    @DisplayName("A key cannot change its own scopes, whether long-lived or a session key, and keeps them")
    void aKeyCannotChangeItsOwnScopes() throws Exception {
        final ObjectId user = seedUser("user");
        final String key = seedKey(user, Set.of("api-keys:write", "policies:read"));
        final HttpResponse<String> refused = send("PUT", "/api/api-keys/" + idOf(key) + "/scopes", key, "{\"scopes\":[\"api-keys:write\"]}");
        assertEquals(409, refused.statusCode(), refused.body());
        assertTrue(gson.fromJson(refused.body(), JsonObject.class).get("message").getAsString().contains("making the request"));
        assertEquals(200, send("GET", "/api/policies", key, null).statusCode(), "the key keeps its scopes");

        final ai.philterd.philter.data.entities.ApiKeyEntity session =
                apiKeyDataService.createSessionKey("req", user, ApiKeyScope.all(), "test", null);
        assertEquals(409, send("PUT", "/api/api-keys/" + session.getId().toHexString() + "/scopes",
                session.getApiKey(), "{\"scopes\":[\"redact\"]}").statusCode());

        // Another key may still change it.
        assertEquals(200, send("PUT", "/api/api-keys/" + idOf(key) + "/scopes", session.getApiKey(),
                "{\"scopes\":[\"api-keys:write\"]}").statusCode());
    }

}
