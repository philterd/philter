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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Passwords over the API, end to end: setting one at creation, an administrator's reset, a user's own
 * change, the first password on the calling administrator's own user, revocation of session keys, and
 * the audit trail, which must never carry a password.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class UserPasswordsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String FIRST = "first-password-0123456789";
    private static final String SECOND = "second-password-0123456789";
    private static final String THIRD = "third-password-0123456789";

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
    private ObjectId adminUserId;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        adminUserId = seedUser("admin");
        adminKey = seedKey(adminUserId, ApiKeyScope.all(), false);
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private ObjectId seedUser(final String role) {
        final String username = "pw-" + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return userService.findByUsername(username).getId();
    }

    /** Session keys are only issued by sign-in, which does not exist yet, so the flag is set directly. */
    private String seedKey(final ObjectId userId, final Set<String> scopes, final boolean session) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        if (session) {
            mongoClient.getDatabase("philter").getCollection("api_keys").updateOne(
                    Filters.eq("_id", apiKeyDataService.findOneByApiKey(response.getMessage()).getId()),
                    Updates.set("session", true));
        }
        return response.getMessage();
    }

    private String username(final ObjectId userId) {
        return userService.findOneById(userId).getUsername();
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey, final String body)
            throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String changeBody(final String current, final String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    private List<JsonObject> auditEvents(final String event, final ObjectId associatedObject) throws Exception {
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=500&event=" + event, adminKey, null);
        assertEquals(200, audit.statusCode(), audit.body());
        final List<JsonObject> found = new ArrayList<>();
        for (final JsonElement element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = element.getAsJsonObject();
            if (e.has("associatedObject") && associatedObject.toHexString().equals(e.get("associatedObject").getAsString())) {
                found.add(e);
            }
        }
        return found;
    }

    @Test
    @DisplayName("A password set at creation is hashed, must be changed, and is never returned")
    void createsAUserWithAPassword() throws Exception {

        final String username = "pw-" + UUID.randomUUID();
        assertEquals(201, send("POST", "/api/users", adminKey,
                "{\"username\":\"" + username + "\",\"password\":\"" + FIRST + "\"}").statusCode());

        final UserEntity user = userService.findByUsername(username);
        assertNotEquals(FIRST, user.getPassword(), "the password is stored as a hash");
        assertTrue(user.getPassword().startsWith("$2"), "bcrypt");
        assertTrue(userService.passwordMatches(user, FIRST));
        assertTrue(user.isPasswordChangeRequired(), "an administrator chose it, so the user must change it");

        final HttpResponse<String> read = send("GET", "/api/users/" + username, adminKey, null);
        final JsonObject body = gson.fromJson(read.body(), JsonObject.class);
        assertTrue(body.get("passwordSet").getAsBoolean());
        assertTrue(body.get("passwordChangeRequired").getAsBoolean());
        assertFalse(read.body().contains(user.getPassword()), "the hash must not leave the API");
        assertFalse(read.body().contains(FIRST));

        final List<JsonObject> set = auditEvents("user_password_set", user.getId());
        assertEquals(1, set.size());
        assertEquals(adminUserId.toHexString(), set.getFirst().get("apiKeyId").getAsString());
        assertFalse(set.getFirst().toString().contains(FIRST), "the audit log must not carry the password");

    }

    @Test
    @DisplayName("A user created without a password has none")
    void createsAUserWithoutAPassword() throws Exception {
        final String username = "pw-" + UUID.randomUUID();
        assertEquals(201, send("POST", "/api/users", adminKey, "{\"username\":\"" + username + "\"}").statusCode());
        assertNull(userService.findByUsername(username).getPassword());
        assertFalse(gson.fromJson(send("GET", "/api/users/" + username, adminKey, null).body(), JsonObject.class)
                .get("passwordSet").getAsBoolean());
    }

    @Test
    @DisplayName("Passwords under 16 characters or over 72 UTF-8 bytes are refused everywhere")
    void refusesUnacceptablePasswords() throws Exception {

        final String tooShort = "fifteen-chars-x";
        // 25 three-byte characters: well over 16 characters, 75 bytes.
        final String tooLong = "€".repeat(25);

        assertEquals(400, send("POST", "/api/users", adminKey,
                "{\"username\":\"pw-" + UUID.randomUUID() + "\",\"password\":\"" + tooShort + "\"}").statusCode());
        assertEquals(400, send("POST", "/api/users", adminKey,
                "{\"username\":\"pw-" + UUID.randomUUID() + "\",\"password\":\"" + tooLong + "\"}").statusCode());

        final ObjectId user = seedUser("user");
        assertEquals(400, send("PUT", "/api/users/" + username(user) + "/password", adminKey,
                "{\"password\":\"" + tooShort + "\"}").statusCode());
        assertNull(userService.findOneById(user).getPassword(), "nothing was saved");

    }

    @Test
    @DisplayName("An administrator resets another user's password, forcing a change and revoking session keys only")
    void resetsAnotherUsersPassword() throws Exception {

        final ObjectId user = seedUser("user");
        final String longLived = seedKey(user, Set.of(ApiKeyScope.USERS_READ.getScope()), false);
        final String session = seedKey(user, Set.of(ApiKeyScope.USERS_READ.getScope()), true);
        assertEquals(200, send("GET", "/api/users/me", session, null).statusCode());

        assertEquals(204, send("PUT", "/api/users/" + username(user) + "/password", adminKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());
        assertEquals(204, send("PUT", "/api/users/" + username(user) + "/password", adminKey,
                "{\"password\":\"" + SECOND + "\"}").statusCode());

        final UserEntity stored = userService.findOneById(user);
        assertTrue(userService.passwordMatches(stored, SECOND));
        assertTrue(stored.isPasswordChangeRequired());

        assertEquals(401, send("GET", "/api/users/me", session, null).statusCode(), "the session key is revoked");
        assertEquals(200, send("GET", "/api/users/me", longLived, null).statusCode(), "long-lived keys are unaffected");

        assertEquals(1, auditEvents("user_password_set", user).size(), "the first was a set");
        final List<JsonObject> reset = auditEvents("user_password_reset", user);
        assertEquals(1, reset.size(), "the second replaced a password");
        assertTrue(reset.getFirst().get("details").getAsString().contains("change_required: true"));
        assertFalse(reset.getFirst().toString().contains(SECOND));

    }

    @Test
    @DisplayName("A user changes their own password with the current one, clearing the required change")
    void changesOwnPassword() throws Exception {

        final ObjectId user = seedUser("user");
        assertEquals(204, send("PUT", "/api/users/" + username(user) + "/password", adminKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());
        final String own = seedKey(user, Set.of(ApiKeyScope.USERS_WRITE.getScope()), false);
        final String session = seedKey(user, Set.of(ApiKeyScope.USERS_READ.getScope()), true);

        assertEquals(403, send("PUT", "/api/users/me/password", own, changeBody(SECOND, THIRD)).statusCode(),
                "a wrong current password");
        assertEquals(400, send("PUT", "/api/users/me/password", own, changeBody(FIRST, FIRST)).statusCode(),
                "the same password");
        assertEquals(400, send("PUT", "/api/users/me/password", own, changeBody(FIRST, "short")).statusCode());
        assertEquals(400, send("PUT", "/api/users/me/password", own, "{\"newPassword\":\"" + SECOND + "\"}").statusCode());
        assertTrue(userService.passwordMatches(userService.findOneById(user), FIRST), "nothing changed yet");
        assertEquals(200, send("GET", "/api/users/me", session, null).statusCode(), "nor was anything revoked");

        assertEquals(204, send("PUT", "/api/users/me/password", own, changeBody(FIRST, SECOND)).statusCode());

        final UserEntity stored = userService.findOneById(user);
        assertTrue(userService.passwordMatches(stored, SECOND));
        assertFalse(stored.isPasswordChangeRequired());
        assertEquals(401, send("GET", "/api/users/me", session, null).statusCode(), "session keys are revoked");

        final List<JsonObject> changed = auditEvents("user_password_changed", user);
        assertEquals(1, changed.size());
        assertEquals(user.toHexString(), changed.getFirst().get("apiKeyId").getAsString());
        assertFalse(changed.getFirst().toString().contains(FIRST) || changed.getFirst().toString().contains(SECOND));

    }

    @Test
    @DisplayName("A user without a password cannot set one by changing it")
    void cannotChangeAPasswordThatDoesNotExist() throws Exception {
        final ObjectId user = seedUser("user");
        final String own = seedKey(user, Set.of(ApiKeyScope.USERS_WRITE.getScope()), false);
        assertEquals(409, send("PUT", "/api/users/me/password", own, changeBody(FIRST, SECOND)).statusCode());
        assertNull(userService.findOneById(user).getPassword());
    }

    @Test
    @DisplayName("An administrator's key sets its own user's first password, and only the first")
    void setsOwnFirstPasswordWithAnAdministratorKey() throws Exception {

        // As the bootstrap key does for the admin user: an all-scope key on an administrator with no password.
        assertEquals(204, send("PUT", "/api/users/" + username(adminUserId) + "/password", adminKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());
        final UserEntity stored = userService.findOneById(adminUserId);
        assertTrue(userService.passwordMatches(stored, FIRST));
        assertFalse(stored.isPasswordChangeRequired(), "the administrator chose it");

        assertEquals(409, send("PUT", "/api/users/" + username(adminUserId) + "/password", adminKey,
                "{\"password\":\"" + SECOND + "\"}").statusCode(), "after that, the current password is required");
        assertTrue(userService.passwordMatches(userService.findOneById(adminUserId), FIRST));

    }

    @Test
    @DisplayName("The bootstrap API key sets the admin user's first password")
    void theBootstrapKeySetsTheAdminPassword() throws Exception {

        // The integration tests start Philter with this key (see pom.xml), so it is seeded onto "admin".
        final String bootstrapKey = System.getenv("PHILTER_BOOTSTRAP_API_KEY");
        final UserEntity admin = userService.findByUsername("admin");
        assertNull(admin.getPassword(), "the admin user starts without a password");

        assertEquals(204, send("PUT", "/api/users/admin/password", bootstrapKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());

        final UserEntity stored = userService.findByUsername("admin");
        assertTrue(userService.passwordMatches(stored, FIRST));
        assertFalse(stored.isPasswordChangeRequired());
        assertEquals(204, send("PUT", "/api/users/me/password", bootstrapKey, changeBody(FIRST, SECOND)).statusCode(),
                "and from then on it changes like any other");

    }

    @Test
    @DisplayName("A non-administrator cannot set another user's password, and a key without users:write cannot change its own")
    void requiresTheRightCaller() throws Exception {

        final ObjectId user = seedUser("user");
        final ObjectId other = seedUser("user");
        final String userKey = seedKey(user, ApiKeyScope.all(), false);
        assertEquals(403, send("PUT", "/api/users/" + username(other) + "/password", userKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());
        assertNull(userService.findOneById(other).getPassword());

        assertEquals(204, send("PUT", "/api/users/" + username(user) + "/password", adminKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());
        final String readOnly = seedKey(user, Set.of(ApiKeyScope.USERS_READ.getScope()), false);
        assertEquals(403, send("PUT", "/api/users/me/password", readOnly, changeBody(FIRST, SECOND)).statusCode());

        assertEquals(404, send("PUT", "/api/users/no-such-user-" + UUID.randomUUID() + "/password", adminKey,
                "{\"password\":\"" + FIRST + "\"}").statusCode());

    }

}
