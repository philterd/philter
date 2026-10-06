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
import ai.philterd.philter.services.mfa.TotpService;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
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
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOTP MFA end to end: enrollment that applies only once confirmed, a sign-in that asks for a code, codes
 * that work once, the lock after five bad codes, removal and unlocking, the two settings, and a secret
 * that is encrypted at rest and never returned again.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class MfaApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String PASSWORD = "an-mfa-test-password-0123";

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
    private final TotpService totp = new TotpService();

    private HttpClient httpClient;
    private String baseUrl;
    private ObjectId adminUserId;
    private String adminKey;

    @BeforeEach
    void setUp() throws Exception {
        SignInConfig.setOverrideForTesting(true);
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        adminUserId = seedUser("admin");
        adminKey = apiKeyDataService.createApiKey("req", adminUserId, "test", ApiKeyScope.all()).getMessage();
        settings(true, false);
    }

    @AfterEach
    void tearDown() throws Exception {
        settings(false, false);
        SignInConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private void settings(final boolean available, final boolean required) throws Exception {
        final HttpResponse<String> response = send("PATCH", "/api/settings", adminKey,
                "{\"mfaRequired\":" + required + ",\"mfaAvailable\":" + available + "}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private ObjectId seedUser(final String role) {
        final String username = "mfa-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, role, policyDataService, contextDataService, "test").isSuccessful());
        final UserEntity user = userService.findByUsername(username);
        assertTrue(userService.setPassword("req", user, PASSWORD, false, "test", null, null).isSuccessful());
        return user.getId();
    }

    private String username(final ObjectId userId) {
        return userService.findOneById(userId).getUsername();
    }

    private String longLivedKey(final ObjectId userId) {
        return apiKeyDataService.createApiKey("req", userId, "test", ApiKeyScope.all()).getMessage();
    }

    private HttpResponse<String> send(final String method, final String path, final String apiKey, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (apiKey != null) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject signIn(final ObjectId userId) throws Exception {
        final HttpResponse<String> response = send("POST", "/api/sign-in", null,
                "{\"username\":\"" + username(userId) + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(200, response.statusCode(), response.body());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private HttpResponse<String> completeSignIn(final String challenge, final String code) throws Exception {
        return send("POST", "/api/sign-in/mfa", null, "{\"challenge\":\"" + challenge + "\",\"code\":\"" + code + "\"}");
    }

    private static String codeBody(final String code) {
        return "{\"code\":\"" + code + "\"}";
    }

    /** Enrolls the user through the API and returns the secret. */
    private String enroll(final String key) throws Exception {
        final HttpResponse<String> started = send("POST", "/api/users/me/mfa", key, null);
        assertEquals(200, started.statusCode(), started.body());
        final String secret = gson.fromJson(started.body(), JsonObject.class).get("secret").getAsString();
        assertEquals(204, send("POST", "/api/users/me/mfa/confirm", key,
                codeBody(totp.codeAt(secret, TotpService.currentTimeStep()))).statusCode());
        return secret;
    }

    /** A code the user has not used yet: the next step, which the one-step window still accepts. */
    private String nextCode(final String secret) {
        return totp.codeAt(secret, TotpService.currentTimeStep() + 1);
    }

    private int audited(final String event, final ObjectId userId) throws Exception {
        final HttpResponse<String> audit = send("GET", "/api/audit?limit=500&event=" + event, adminKey, null);
        int count = 0;
        for (final JsonElement element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = element.getAsJsonObject();
            if (e.has("associatedObject") && userId.toHexString().equals(e.get("associatedObject").getAsString())) {
                count++;
            }
        }
        return count;
    }

    @Test
    @DisplayName("Enrollment applies only once confirmed, and the secret is encrypted and never shown again")
    void enrollsOnlyOnConfirmation() throws Exception {

        final ObjectId user = seedUser("user");
        final String key = longLivedKey(user);

        final HttpResponse<String> started = send("POST", "/api/users/me/mfa", key, null);
        assertEquals(200, started.statusCode(), started.body());
        final JsonObject enrollment = gson.fromJson(started.body(), JsonObject.class);
        final String secret = enrollment.get("secret").getAsString();
        assertTrue(enrollment.get("otpauthUri").getAsString().startsWith("otpauth://totp/Philter:"));

        // Not yet confirmed: sign-in still issues a key on the password.
        assertTrue(signIn(user).has("apiKey"), "a pending enrollment does not apply");

        assertEquals(400, send("POST", "/api/users/me/mfa/confirm", key, codeBody("000000")).statusCode());
        assertEquals(204, send("POST", "/api/users/me/mfa/confirm", key,
                codeBody(totp.codeAt(secret, TotpService.currentTimeStep()))).statusCode());
        assertEquals(409, send("POST", "/api/users/me/mfa", key, null).statusCode(), "already enrolled");

        final HttpResponse<String> read = send("GET", "/api/users/me", key, null);
        assertTrue(gson.fromJson(read.body(), JsonObject.class).get("mfaEnabled").getAsBoolean());
        assertFalse(read.body().contains(secret), "the secret is never returned again");

        final Document stored = mongoClient.getDatabase("philter").getCollection("users").find(new Document("_id", user)).first();
        assertNotEquals(secret, stored.getString("mfa_secret"), "encrypted at rest");
        assertFalse(stored.toJson().contains(secret));
        assertFalse(stored.getString("mfa_secret_key").isEmpty(), "with its data key");
        assertEquals(1, audited("user_mfa_enrolled", user));

    }

    @Test
    @DisplayName("An enrolled user gets a single-use challenge, never a key on the password alone, and each code works once")
    void signsInWithAChallengeAndACode() throws Exception {

        final ObjectId user = seedUser("user");
        final String secret = enroll(longLivedKey(user));

        final JsonObject first = signIn(user);
        assertFalse(first.has("apiKey"), "no key on the password alone");
        assertTrue(first.get("mfaRequired").getAsBoolean());
        final String challenge = first.get("challenge").getAsString();

        final String code = nextCode(secret);
        final HttpResponse<String> completed = completeSignIn(challenge, code);
        assertEquals(200, completed.statusCode(), completed.body());
        final String sessionKey = gson.fromJson(completed.body(), JsonObject.class).get("apiKey").getAsString();
        assertEquals(200, send("GET", "/api/users/me", sessionKey, null).statusCode());
        assertEquals(apiKeyDataService.findOneByApiKey(sessionKey).getId().toHexString(),
                gson.fromJson(completed.body(), JsonObject.class).get("id").getAsString(),
                "completing sign-in with a code returns the session key's id too");

        assertEquals(401, completeSignIn(challenge, code).statusCode(), "the challenge is single-use");
        assertEquals(401, completeSignIn(signIn(user).get("challenge").getAsString(), code).statusCode(),
                "the code is single-use, even with a new challenge");

        final String expiring = signIn(user).get("challenge").getAsString();
        mongoClient.getDatabase("philter").getCollection("sign_in_challenges").updateMany(new Document(),
                new Document("$set", new Document("expires_at", new Date(System.currentTimeMillis() - 1000))));
        assertEquals(401, completeSignIn(expiring, totp.codeAt(secret, TotpService.currentTimeStep() - 1)).statusCode(),
                "an expired challenge is refused");

    }

    @Test
    @DisplayName("Five bad codes lock the user until an administrator unlocks them")
    void locksAfterFiveBadCodes() throws Exception {

        final ObjectId user = seedUser("user");
        final String secret = enroll(longLivedKey(user));

        for (int i = 0; i < UserService.MAX_MFA_ATTEMPTS; i++) {
            assertNotEquals(200, completeSignIn(signIn(user).get("challenge").getAsString(), "000000").statusCode());
        }
        assertTrue(userService.findOneById(user).isMfaLocked());
        assertEquals(1, audited("user_mfa_locked", user), "the lock is audited once");

        final HttpResponse<String> passwordStep = send("POST", "/api/sign-in", null,
                "{\"username\":\"" + username(user) + "\",\"password\":\"" + PASSWORD + "\"}");
        assertEquals(403, passwordStep.statusCode(), "even the right password and code cannot get past the lock");

        assertEquals(403, send("POST", "/api/users/" + username(user) + "/mfa/unlock", longLivedKey(seedUser("user")), null)
                .statusCode(), "only an administrator unlocks");
        assertEquals(204, send("POST", "/api/users/" + username(user) + "/mfa/unlock", adminKey, null).statusCode());
        assertEquals(409, send("POST", "/api/users/" + username(user) + "/mfa/unlock", adminKey, null).statusCode());
        assertEquals(1, audited("user_mfa_unlocked", user));

        assertEquals(200, completeSignIn(signIn(user).get("challenge").getAsString(), nextCode(secret)).statusCode());

    }

    @Test
    @DisplayName("A good code resets the count, so only consecutive bad codes lock")
    void onlyConsecutiveBadCodesLock() throws Exception {
        final ObjectId user = seedUser("user");
        final String secret = enroll(longLivedKey(user));
        for (int i = 0; i < UserService.MAX_MFA_ATTEMPTS - 1; i++) {
            completeSignIn(signIn(user).get("challenge").getAsString(), "000000");
        }
        assertEquals(200, completeSignIn(signIn(user).get("challenge").getAsString(), nextCode(secret)).statusCode());
        completeSignIn(signIn(user).get("challenge").getAsString(), "000000");
        assertFalse(userService.findOneById(user).isMfaLocked());
    }

    @Test
    @DisplayName("A user removes their own MFA with a code; an administrator removes another's, clearing a lock")
    void removesEnrollment() throws Exception {

        final ObjectId user = seedUser("user");
        final String key = longLivedKey(user);
        final String secret = enroll(key);

        assertEquals(403, send("POST", "/api/users/me/mfa/remove", key, codeBody("000000")).statusCode());
        assertEquals(204, send("POST", "/api/users/me/mfa/remove", key, codeBody(nextCode(secret))).statusCode());
        assertFalse(userService.findOneById(user).isMfaEnabled());
        assertTrue(signIn(user).has("apiKey"), "sign-in no longer asks for a code");
        assertEquals(1, audited("user_mfa_removed", user));

        final ObjectId other = seedUser("user");
        enroll(longLivedKey(other));
        for (int i = 0; i < UserService.MAX_MFA_ATTEMPTS; i++) {
            completeSignIn(signIn(other).get("challenge").getAsString(), "000000");
        }
        assertTrue(userService.findOneById(other).isMfaLocked());
        assertEquals(403, send("DELETE", "/api/users/" + username(other) + "/mfa", key, null).statusCode(),
                "a non-administrator cannot remove another user's MFA");
        assertEquals(204, send("DELETE", "/api/users/" + username(other) + "/mfa", adminKey, null).statusCode());
        final UserEntity cleared = userService.findOneById(other);
        assertFalse(cleared.isMfaEnabled());
        assertFalse(cleared.isMfaLocked(), "removing the enrollment clears the lock");
        assertEquals(409, send("DELETE", "/api/users/" + username(other) + "/mfa", adminKey, null).statusCode());

        assertEquals(409, send("DELETE", "/api/users/" + username(adminUserId) + "/mfa", adminKey, null).statusCode(),
                "an administrator removes their own MFA with a code, like anyone else");

    }

    @Test
    @DisplayName("Enrollment needs MFA to be available, and requiring it needs it available")
    void settingsGateEnrollment() throws Exception {
        settings(false, false);
        assertEquals(409, send("POST", "/api/users/me/mfa", longLivedKey(seedUser("user")), null).statusCode());
        assertEquals(400, send("PATCH", "/api/settings", adminKey, "{\"mfaRequired\":true}").statusCode());
    }

    @Test
    @DisplayName("When MFA is required, an unenrolled user gets a key that can only enroll, then signs in with a code")
    void requiredMfaForcesEnrollment() throws Exception {

        settings(true, true);
        final ObjectId user = seedUser("user");

        final JsonObject first = signIn(user);
        assertTrue(first.get("mfaEnrollmentRequired").getAsBoolean());
        final String restricted = first.get("apiKey").getAsString();
        assertEquals(403, send("GET", "/api/users/me", restricted, null).statusCode(), "nothing but enrollment");

        final String secret = enroll(restricted);
        assertEquals(401, send("POST", "/api/users/me/mfa", restricted, null).statusCode(),
                "confirming revokes the session keys, so the person signs in again");

        final JsonObject second = signIn(user);
        assertTrue(second.get("mfaRequired").getAsBoolean());
        final HttpResponse<String> completed = completeSignIn(second.get("challenge").getAsString(), nextCode(secret));
        assertEquals(200, completed.statusCode(), completed.body());
        assertFalse(gson.fromJson(completed.body(), JsonObject.class).get("mfaEnrollmentRequired").getAsBoolean());

    }

    @Test
    @DisplayName("An enrolled user is still challenged after MFA is made unavailable")
    void turningMfaOffDoesNotSkipTheCode() throws Exception {
        final ObjectId user = seedUser("user");
        enroll(longLivedKey(user));
        settings(false, false);
        assertFalse(signIn(user).has("apiKey"));
    }

}
