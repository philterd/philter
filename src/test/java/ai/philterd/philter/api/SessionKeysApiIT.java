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

import ai.philterd.philter.data.entities.ApiKeyEntity;
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
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session keys over the API: they work like any key until they expire, expiry is decided by the
 * database rather than a node's cache, the holder can sign out, an administrator can revoke a user's
 * sessions, and listings tell them apart from long-lived keys.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class SessionKeysApiIT {

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

    @Autowired
    private MongoClient mongoClient;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String adminKey;

    private ObjectId adminUserId;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        adminUserId = seedUser("admin");
        adminKey = apiKeyDataService.createApiKey("req", adminUserId, "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private ObjectId seedUser(final String role) {
        final String username = "sk-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return userService.findByUsername(username).getId();
    }

    private String session(final ObjectId userId, final Set<String> scopes) {
        return apiKeyDataService.createSessionKey("req", userId, scopes, "test", null).getApiKey();
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void expireIdleWindow(final String apiKey) {
        mongoClient.getDatabase("philter").getCollection("api_keys").updateOne(
                Filters.eq("_id", apiKeyDataService.findOneByApiKey(apiKey).getId()),
                Updates.set("idle_expires_at", new Date(System.currentTimeMillis() - 1000)));
    }

    private boolean audited(final String event, final String detail) throws Exception {
        return countAudited(event, detail) > 0;
    }

    private int countAudited(final String event, final String detail) throws Exception {
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=500&event=" + event, adminKey);
        assertEquals(200, audit.statusCode(), audit.body());
        int count = 0;
        for (final JsonElement element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = element.getAsJsonObject();
            if (e.has("details") && e.get("details").getAsString().contains(detail)) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("A session key that has gone idle is refused even while a node holds it in its cache")
    void expiryIsDecidedByTheDatabaseNotTheCache() throws Exception {

        final ObjectId user = seedUser("user");
        final String key = session(user, Set.of(ApiKeyScope.USERS_READ.getScope()));
        final ApiKeyEntity issued = apiKeyDataService.findOneByApiKey(key);

        // The first request caches the key, as each node behind a load balancer would.
        assertEquals(200, send("GET", "/api/users/me", key).statusCode());

        expireIdleWindow(key);

        assertEquals(401, send("GET", "/api/users/me", key).statusCode(), "the cached copy must not keep it alive");
        assertEquals(401, send("GET", "/api/users/me", key).statusCode());
        assertTrue(audited("api_key_expired", "reason: idle timeout"), "the expiry is audited");
        assertTrue(apiKeyDataService.findAll(user, 0, 10, true).stream()
                .filter(k -> k.getId().equals(issued.getId())).allMatch(ApiKeyEntity::isDeleted));

    }

    @Test
    @DisplayName("Each request moves the idle window forward")
    void activityKeepsASessionAlive() throws Exception {
        final String key = session(seedUser("user"), Set.of(ApiKeyScope.USERS_READ.getScope()));
        final Date before = apiKeyDataService.findOneByApiKey(key).getIdleExpiresAt();
        Thread.sleep(10);
        assertEquals(200, send("GET", "/api/users/me", key).statusCode());
        assertTrue(apiKeyDataService.findOneByApiKey(key).getIdleExpiresAt().after(before));
    }

    @Test
    @DisplayName("A session key signs out without any particular scope; a long-lived key cannot sign itself out")
    void signsOut() throws Exception {

        final ObjectId user = seedUser("user");
        final String key = session(user, Set.of(ApiKeyScope.REDACT.getScope()));

        assertEquals(204, send("DELETE", "/api/api-keys/current", key).statusCode());
        assertEquals(401, send("DELETE", "/api/api-keys/current", key).statusCode(), "the key no longer works");
        assertTrue(audited("api_key_deleted", "reason: signed out"));

        final String longLived = apiKeyDataService.createApiKey("req", user, "test", Set.of(ApiKeyScope.REDACT.getScope())).getMessage();
        assertEquals(409, send("DELETE", "/api/api-keys/current", longLived).statusCode());
        assertNotNull(apiKeyDataService.findOneByApiKey(longLived), "a long-lived key is not revoked this way");

    }

    @Test
    @DisplayName("An administrator revokes all of a user's session keys and nothing else")
    void revokesAUsersSessions() throws Exception {

        final ObjectId user = seedUser("user");
        final String username = userService.findOneById(user).getUsername();
        final String first = session(user, Set.of(ApiKeyScope.USERS_READ.getScope()));
        final String second = session(user, Set.of(ApiKeyScope.USERS_READ.getScope()));
        final String longLived = apiKeyDataService.createApiKey("req", user, "test", ApiKeyScope.all()).getMessage();

        assertEquals(403, send("DELETE", "/api/users/" + username + "/session-keys", longLived).statusCode(),
                "a non-administrator is refused");

        final HttpResponse<String> revoked = send("DELETE", "/api/users/" + username + "/session-keys", adminKey);
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(2, gson.fromJson(revoked.body(), JsonObject.class).get("revoked").getAsInt());
        // Each administrator's key is new to this test, so the detail naming it matches only these events.
        assertEquals(2, countAudited("api_key_deleted", "reason: revoked by user: " + adminUserId),
                "one api_key_deleted event per session key, naming the administrator");

        assertEquals(401, send("GET", "/api/users/me", first).statusCode());
        assertEquals(401, send("GET", "/api/users/me", second).statusCode());
        assertEquals(200, send("GET", "/api/users/me", longLived).statusCode());
        assertEquals(404, send("DELETE", "/api/users/no-such-" + UUID.randomUUID() + "/session-keys", adminKey).statusCode());

    }

    @Test
    @DisplayName("Listings tell session keys from long-lived keys")
    void listingsDistinguishSessionKeys() throws Exception {

        final ObjectId user = seedUser("user");
        final String key = session(user, Set.of(ApiKeyScope.API_KEYS_READ.getScope()));
        apiKeyDataService.createApiKey("req", user, "test", Set.of(ApiKeyScope.REDACT.getScope()));

        final HttpResponse<String> listed = send("GET", "/api/api-keys", key);
        assertEquals(200, listed.statusCode(), listed.body());

        int sessions = 0;
        int longLived = 0;
        for (final JsonElement element : gson.fromJson(listed.body(), JsonObject.class).getAsJsonArray("apiKeys")) {
            final JsonObject k = element.getAsJsonObject();
            if (k.get("session").getAsBoolean()) {
                sessions++;
                assertTrue(k.has("expiresAt") && k.has("idleExpiresAt") && k.has("lastUsedAt"), k.toString());
            } else {
                longLived++;
                assertFalse(k.has("expiresAt") && !k.get("expiresAt").isJsonNull(), "a long-lived key has no expiry: " + k);
            }
        }
        assertEquals(1, sessions);
        assertEquals(1, longLived);

    }

}
