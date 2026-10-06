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

import ai.philterd.philter.config.SignInConfig;
import ai.philterd.philter.data.entities.UserEntity;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Password sign-in end to end: off by default, a session key for valid credentials, one answer for
 * every kind of failure, a key that can only change the password when one must be changed, and no
 * way to turn a session into a long-lived key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class SignInApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String PASSWORD = "a-sign-in-password-0123";
    private static final String NEW_PASSWORD = "a-new-sign-in-password-0123";

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
    private String adminKey;

    @BeforeEach
    void setUp() {
        SignInConfig.setOverrideForTesting(true);
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        adminKey = apiKeyDataService.createApiKey("req", seedUser("admin", null, false), "test", ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        SignInConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    /** A user with the password set as the user's own choice (no change required), unless told otherwise. */
    private ObjectId seedUser(final String role, final String password, final boolean changeRequired) {
        final String username = "si-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, role, policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful());
        final UserEntity user = userService.findByUsername(username);
        if (password != null) {
            assertTrue(userService.setPassword("req", user, password, changeRequired, "test", null, null).isSuccessful());
        }
        return user.getId();
    }

    private String username(final ObjectId userId) {
        return userService.findOneById(userId).getUsername();
    }

    private HttpResponse<String> signIn(final String username, final String password) throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("password", password);
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/sign-in"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey, final String body)
            throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private List<JsonObject> audit(final String event, final String detail) throws Exception {
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=500&event=" + event, adminKey, null);
        assertEquals(200, audit.statusCode(), audit.body());
        final List<JsonObject> found = new java.util.ArrayList<>();
        for (final JsonElement element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = element.getAsJsonObject();
            if (e.has("details") && e.get("details").getAsString().contains(detail)) {
                found.add(e);
            }
        }
        return found;
    }

    @Test
    @DisplayName("Sign-in is not found unless it is enabled")
    void disabledByDefault() throws Exception {
        final ObjectId user = seedUser("user", PASSWORD, false);
        SignInConfig.setOverrideForTesting(false);
        final HttpResponse<String> response = signIn(username(user), PASSWORD);
        assertEquals(404, response.statusCode());
        assertFalse(response.body().contains("sk_"));

        // Nothing about the request may reveal that either step exists while sign-in is off.
        for (final String path : List.of("/api/sign-in", "/api/sign-in/mfa")) {
            for (final String contentType : List.of("application/json", "text/plain")) {
                final HttpResponse<String> malformed = httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString("{not json"))
                        .build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, malformed.statusCode(), path + " " + contentType + ": " + malformed.body());
            }
        }
    }

    @Test
    @DisplayName("Valid credentials return a working session key with every scope, and the sign-in is audited")
    void signsIn() throws Exception {

        final ObjectId user = seedUser("user", PASSWORD, false);
        final HttpResponse<String> response = signIn(username(user), PASSWORD);
        assertEquals(200, response.statusCode(), response.body());

        final JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        final String key = body.get("apiKey").getAsString();
        assertEquals(username(user), body.get("username").getAsString());
        assertFalse(body.get("passwordChangeRequired").getAsBoolean());
        assertNotNull(body.get("expiresAt"));
        assertNotNull(body.get("idleExpiresAt"));
        final Set<String> scopes = new HashSet<>();
        body.getAsJsonArray("scopes").forEach(scope -> scopes.add(scope.getAsString()));
        assertEquals(ApiKeyScope.all(), scopes);

        assertEquals(200, send("GET", "/api/users/me", key, null).statusCode());
        assertTrue(apiKeyDataService.findOneByApiKey(key).isSession());

        // The response names the session key's id, which the key listing uses, so a client can find its own.
        final String id = body.get("id").getAsString();
        assertEquals(apiKeyDataService.findOneByApiKey(key).getId().toHexString(), id);
        final HttpResponse<String> sessions = send("GET", "/api/api-keys?session=true", key, null);
        assertEquals(200, sessions.statusCode(), sessions.body());
        assertTrue(sessions.body().contains("\"id\":\"" + id + "\""), sessions.body());

        final List<JsonObject> succeeded = audit("sign_in_succeeded", "username: " + username(user));
        assertEquals(1, succeeded.size());
        assertEquals(user.toHexString(), succeeded.getFirst().get("apiKeyId").getAsString());
        assertTrue(succeeded.getFirst().has("clientIpAddress"), succeeded.getFirst().toString());
        assertFalse(succeeded.getFirst().toString().contains(PASSWORD));

    }

    @Test
    @DisplayName("A wrong password, an unknown user, a user without a password, and a deactivated user get the same answer")
    void everyFailureLooksTheSame() throws Exception {

        final ObjectId withPassword = seedUser("user", PASSWORD, false);
        final ObjectId withoutPassword = seedUser("user", null, false);
        final ObjectId deactivated = seedUser("user", PASSWORD, false);
        assertTrue(userService.deactivateUser("req", userService.findOneById(deactivated), "test").isSuccessful());

        final HttpResponse<String> wrongPassword = signIn(username(withPassword), "not-the-password-0123");
        final List<HttpResponse<String>> failures = List.of(
                wrongPassword,
                signIn("no-such-user-" + UUID.randomUUID(), PASSWORD),
                signIn(username(withoutPassword), PASSWORD),
                signIn(username(deactivated), PASSWORD),
                signIn(username(withPassword), null));

        for (final HttpResponse<String> failure : failures) {
            assertEquals(401, failure.statusCode());
            assertEquals(wrongPassword.body(), failure.body(), "the body must not say why");
        }

        final List<JsonObject> failed = audit("sign_in_failed", "username: " + username(withPassword));
        assertEquals(2, failed.size(), "the wrong password and the missing password");
        assertFalse(failed.toString().contains("not-the-password-0123"), "the password is never audited");

    }

    @Test
    @DisplayName("An unknown user costs a bcrypt comparison too, so the time taken does not reveal it")
    void anUnknownUserTakesAsLongAsAWrongPassword() throws Exception {

        final String username = username(seedUser("user", PASSWORD, false));
        signIn(username, "warm-up-password-0123");

        long wrong = Long.MAX_VALUE;
        long unknown = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            signIn(username, "not-the-password-0123");
            wrong = Math.min(wrong, System.nanoTime() - start);
            start = System.nanoTime();
            signIn("no-such-user-" + UUID.randomUUID(), "not-the-password-0123");
            unknown = Math.min(unknown, System.nanoTime() - start);
        }

        // bcrypt dominates both; without the comparison an unknown user returns in a small fraction of the time.
        assertTrue(unknown * 3 > wrong, "unknown " + unknown + "ns vs wrong password " + wrong + "ns");

    }

    @Test
    @DisplayName("A user who must change their password gets a key that can only do that, then signs in again")
    void forcesAPasswordChange() throws Exception {

        final ObjectId user = seedUser("user", PASSWORD, true);
        final HttpResponse<String> response = signIn(username(user), PASSWORD);
        assertEquals(200, response.statusCode(), response.body());
        final JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(body.get("passwordChangeRequired").getAsBoolean());
        final String key = body.get("apiKey").getAsString();

        assertEquals(403, send("GET", "/api/users/me", key, null).statusCode(), "nothing else is allowed");
        assertEquals(403, send("GET", "/api/policies", key, null).statusCode());

        assertEquals(204, send("PUT", "/api/users/me/password", key,
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}").statusCode());
        assertEquals(401, send("GET", "/api/users/me", key, null).statusCode(), "the change revokes the session");

        final HttpResponse<String> again = signIn(username(user), NEW_PASSWORD);
        assertEquals(200, again.statusCode());
        final JsonObject second = gson.fromJson(again.body(), JsonObject.class);
        assertFalse(second.get("passwordChangeRequired").getAsBoolean());
        assertEquals(200, send("GET", "/api/users/me", second.get("apiKey").getAsString(), null).statusCode());

    }

    @Test
    @DisplayName("A password-change-only key can still sign out")
    void aRestrictedKeyCanSignOut() throws Exception {
        final ObjectId user = seedUser("user", PASSWORD, true);
        final String key = gson.fromJson(signIn(username(user), PASSWORD).body(), JsonObject.class).get("apiKey").getAsString();
        assertEquals(204, send("DELETE", "/api/api-keys/current", key, null).statusCode());
        assertEquals(401, send("PUT", "/api/users/me/password", key,
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}").statusCode());
    }

    @Test
    @DisplayName("A session key cannot create API keys, but can still list them")
    void aSessionCannotMintLongLivedKeys() throws Exception {

        final ObjectId admin = seedUser("admin", PASSWORD, false);
        final String key = gson.fromJson(signIn(username(admin), PASSWORD).body(), JsonObject.class).get("apiKey").getAsString();
        final String other = username(seedUser("user", null, false));

        assertEquals(403, send("POST", "/api/api-keys", key, "{\"scopes\":[\"redact\"]}").statusCode());
        assertEquals(403, send("POST", "/api/users/" + other + "/api-keys", key, "{\"scopes\":[\"redact\"]}").statusCode());
        assertEquals(1, apiKeyDataService.count(admin), "only the session key exists");
        assertEquals(200, send("GET", "/api/api-keys", key, null).statusCode());

    }

    @Test
    @DisplayName("A session key can narrow a key's scopes but not widen them; a long-lived key still can")
    void aSessionCannotWidenAKey() throws Exception {

        final ObjectId user = seedUser("user", PASSWORD, false);
        final String narrow = apiKeyDataService.createApiKey("req", user, "test", Set.of("redact")).getMessage();
        final String wide = apiKeyDataService.createApiKey("req", user, "test", Set.of("redact", "policies:read")).getMessage();
        final String narrowId = apiKeyDataService.findOneByApiKey(narrow).getId().toHexString();
        final String wideId = apiKeyDataService.findOneByApiKey(wide).getId().toHexString();
        final String session = gson.fromJson(signIn(username(user), PASSWORD).body(), JsonObject.class)
                .get("apiKey").getAsString();

        final HttpResponse<String> widened = send("PUT", "/api/api-keys/" + narrowId + "/scopes", session,
                "{\"scopes\":[\"redact\",\"policies:read\"]}");
        assertEquals(403, widened.statusCode(), widened.body());
        assertTrue(widened.body().contains("policies:read"), "the refusal names what it would add: " + widened.body());
        assertEquals(Set.of("redact"), apiKeyDataService.findOneByApiKey(narrow).getScopes(), "nothing changed");

        assertEquals(200, send("PUT", "/api/api-keys/" + wideId + "/scopes", session, "{\"scopes\":[\"redact\"]}").statusCode(),
                "narrowing is allowed");
        assertEquals(Set.of("redact"), apiKeyDataService.findOneByApiKey(wide).getScopes());

        // An administrator's session is held to the same rule on another user's key.
        final ObjectId admin = seedUser("admin", PASSWORD, false);
        final String adminSession = gson.fromJson(signIn(username(admin), PASSWORD).body(), JsonObject.class)
                .get("apiKey").getAsString();
        final HttpResponse<String> adminWidened = send("PUT", "/api/api-keys/" + narrowId + "/scopes", adminSession,
                "{\"scopes\":[\"redact\",\"policies:read\"]}");
        assertEquals(403, adminWidened.statusCode(), adminWidened.body());
        assertTrue(adminWidened.body().contains("can narrow a key's scopes but not widen them"), adminWidened.body());
        assertEquals(Set.of("redact"), apiKeyDataService.findOneByApiKey(narrow).getScopes());

        final String longLived = apiKeyDataService.createApiKey("req", user, "test", ApiKeyScope.all()).getMessage();
        assertEquals(200, send("PUT", "/api/api-keys/" + narrowId + "/scopes", longLived,
                "{\"scopes\":[\"redact\",\"policies:read\"]}").statusCode(), "a long-lived key behaves as before");

    }

    @Test
    @DisplayName("The user's role still decides administrator access")
    void roleDecidesAdministratorAccess() throws Exception {
        final String userKey = gson.fromJson(signIn(username(seedUser("user", PASSWORD, false)), PASSWORD).body(),
                JsonObject.class).get("apiKey").getAsString();
        final String adminSessionKey = gson.fromJson(signIn(username(seedUser("admin", PASSWORD, false)), PASSWORD).body(),
                JsonObject.class).get("apiKey").getAsString();
        assertEquals(403, send("GET", "/api/users", userKey, null).statusCode());
        assertEquals(200, send("GET", "/api/users", adminSessionKey, null).statusCode());
    }

}
