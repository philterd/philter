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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Managing users over real HTTP: an administrator's key creates a user, mints a key for it, and that
 * key then works; deactivating the user stops the key and reactivating restores it. The refusals are
 * exercised here too, because they run in a filter and an interceptor that a controller test does not
 * see the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class UsersApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String PASSWORD = "a-password-of-at-least-16-characters";

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
    private ObjectId adminUserId;

    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        adminUserId = seedUser("provision-admin-", "admin");
        adminKey = seedKey(adminUserId, ApiKeyScope.all());

    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    /** Seeds a user directly, standing in for the account a deployment starts with. */
    private ObjectId seedUser(final String prefix, final String role) {
        final String username = prefix + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return userService.findByUsername(username).getId();
    }

    private String seedKey(final ObjectId userId, final Set<String> scopes) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        return response.getMessage();
    }

    private HttpResponse<String> post(final String path, final String apiKey, final String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(final String path, final String apiKey, final String body) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(final String path, final String apiKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private String newUsername() {
        return "provisioned-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("An administrator provisions a user and a key for it, and the key works")
    void provisionsAUserAndAWorkingKey() throws Exception {

        final String username = newUsername();

        final HttpResponse<String> created = post("/api/users", adminKey,
                "{\"username\":\"" + username + "\"}");

        assertEquals(201, created.statusCode(), created.body());
        assertEquals("user", gson.fromJson(created.body(), JsonObject.class).get("role").getAsString());

        final UserEntity provisioned = userService.findByUsername(username);
        assertNotNull(provisioned, "the user must exist after the endpoint says it does");
        assertEquals("user", provisioned.getRole());

        final HttpResponse<String> minted = post("/api/users/" + username + "/api-keys", adminKey,
                "{\"scopes\":[\"redact\",\"policies:read\"]}");

        assertEquals(201, minted.statusCode(), minted.body());
        final String mintedKey = gson.fromJson(minted.body(), JsonObject.class).get("apiKey").getAsString();
        assertTrue(mintedKey.startsWith("sk_"), "a real key is returned, not a placeholder: " + mintedKey);

        // The credential authenticates as the user it was minted for, carrying exactly the scopes that
        // were asked for and nothing else.
        assertEquals(200, get("/api/policies", mintedKey).statusCode());
        final HttpResponse<String> beyondScope = get("/api/ledger", mintedKey);
        assertEquals(403, beyondScope.statusCode());
        assertTrue(beyondScope.body().contains("ledger:read"), beyondScope.body());

    }

    @Test
    @DisplayName("Both creations are readable through the audit API, naming the administrator")
    void bothCreationsAreAudited() throws Exception {

        final String username = newUsername();
        assertEquals(201, post("/api/users", adminKey,
                "{\"username\":\"" + username + "\"}").statusCode());
        assertEquals(201, post("/api/users/" + username + "/api-keys", adminKey,
                "{\"scopes\":[\"redact\"]}").statusCode());

        final ObjectId provisionedUserId = userService.findByUsername(username).getId();

        final HttpResponse<String> audit = get("/api/audit?limit=100", adminKey);
        assertEquals(200, audit.statusCode(), audit.body());

        JsonObject userCreated = null;
        JsonObject keyCreated = null;
        for (final var element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject event = element.getAsJsonObject();
            final String name = event.get("event").getAsString();
            final String object = event.has("associatedObject") ? event.get("associatedObject").getAsString() : "";
            if ("user_created".equals(name) && provisionedUserId.toHexString().equals(object)) {
                userCreated = event;
            }
            if ("api_key_created".equals(name) && provisionedUserId.toHexString().equals(object)) {
                keyCreated = event;
            }
        }

        assertNotNull(userCreated, "the user creation must be readable through GET /api/audit");
        assertEquals(adminUserId.toHexString(), userCreated.get("apiKeyId").getAsString(),
                "the acting administrator is the principal, not the user that was created");

        assertNotNull(keyCreated, "the key creation must be readable through GET /api/audit");
        assertTrue(keyCreated.get("details").getAsString().contains("created by user: " + adminUserId),
                "the detail must name who asked for the key: " + keyCreated.get("details"));

    }

    @Test
    @DisplayName("Without a credential nothing is created")
    void refusesAnUnauthenticatedCaller() throws Exception {

        final String username = newUsername();

        final HttpResponse<String> anonymous = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/users"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"" + username + "\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertEquals(401, anonymous.statusCode(), anonymous.body());
        assertNull(userService.findByUsername(username));

    }

    @Test
    @DisplayName("A user's lifecycle: create, promote, read, deactivate, reactivate, all audited")
    void managesAUserThroughItsLifecycle() throws Exception {

        final String username = newUsername();
        assertEquals(201, post("/api/users", adminKey,
                "{\"username\":\"" + username + "\",\"email\":\"ops@example.com\"}").statusCode());

        final UserEntity created = userService.findByUsername(username);

        final HttpResponse<String> minted = post("/api/users/" + username + "/api-keys", adminKey,
                "{\"scopes\":[\"users:read\",\"policies:read\"]}");
        final String userKey = gson.fromJson(minted.body(), JsonObject.class).get("apiKey").getAsString();

        // The new user reads itself without being an administrator, and cannot list others.
        final HttpResponse<String> me = get("/api/users/me", userKey);
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(username, gson.fromJson(me.body(), JsonObject.class).get("username").getAsString());
        assertEquals(403, get("/api/users", userKey).statusCode());

        final HttpResponse<String> promoted = put("/api/users/" + username + "/role", adminKey, "{\"role\":\"admin\"}");
        assertEquals(200, promoted.statusCode(), promoted.body());
        assertEquals("admin", userService.findByUsername(username).getRole());

        final HttpResponse<String> read = get("/api/users/" + username, adminKey);
        assertEquals(200, read.statusCode(), read.body());
        final JsonObject readUser = gson.fromJson(read.body(), JsonObject.class);
        assertFalse(readUser.get("passwordSet").getAsBoolean(), read.body());
        assertFalse(read.body().contains("$2"), "no password hash leaves the API: " + read.body());
        assertTrue(readUser.get("created").getAsString().matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}(Z|[+-]\\d\\d:\\d\\d)"),
                "dates are ISO 8601 with an offset, like the rest of the API: " + read.body());
        assertTrue(readUser.has("deactivatedAt") && readUser.get("deactivatedAt").isJsonNull(), read.body());

        final HttpResponse<String> listed = get("/api/users?limit=100", adminKey);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(gson.fromJson(listed.body(), JsonObject.class).get("total").getAsLong() >= 2, listed.body());

        assertEquals(200, post("/api/users/" + username + "/deactivate", adminKey, "").statusCode());
        assertEquals(401, get("/api/policies", userKey).statusCode(), "a deactivated user's key is rejected");
        assertEquals(200, get("/api/users/" + username, adminKey).statusCode(), "a deactivated user is still readable");
        assertEquals(409, post("/api/users/" + username + "/deactivate", adminKey, "").statusCode());

        assertEquals(200, post("/api/users/" + username + "/reactivate", adminKey, "").statusCode());
        assertEquals(200, get("/api/policies", userKey).statusCode(), "reactivation restores the key");

        final String adminKeyId = apiKeyDataService.findOneByApiKey(adminKey).getId().toHexString();
        final HttpResponse<String> audit = get("/api/audit?limit=100", adminKey);
        final Set<String> recorded = new HashSet<>();
        for (final var element : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject event = element.getAsJsonObject();
            if (event.has("associatedObject") && created.getId().toHexString().equals(event.get("associatedObject").getAsString())
                    && adminUserId.toHexString().equals(event.get("apiKeyId").getAsString())
                    && event.has("details") && event.get("details").getAsString().contains("api_key: " + adminKeyId)) {
                recorded.add(event.get("event").getAsString());
            }
        }
        assertTrue(recorded.containsAll(Set.of("user_created", "user_role_changed", "user_deactivated", "user_reactivated")),
                "every change names the acting administrator and key: " + recorded);

    }

    @Test
    @DisplayName("An administrator cannot deactivate their own user")
    void cannotDeactivateSelf() throws Exception {
        final String self = userService.findOneById(adminUserId).getUsername();
        assertEquals(409, post("/api/users/" + self + "/deactivate", adminKey, "").statusCode());
        assertFalse(userService.findOneById(adminUserId).isDeactivated());
    }

    @Test
    @DisplayName("A key without the provisioning scope is refused, and told which scope")
    void refusesAKeyWithoutTheScope() throws Exception {

        final String redactOnly = seedKey(adminUserId, Set.of(ApiKeyScope.REDACT.getScope()));
        final String username = newUsername();

        final HttpResponse<String> refused = post("/api/users", redactOnly,
                "{\"username\":\"" + username + "\"}");

        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("users:write"), refused.body());
        assertNull(userService.findByUsername(username), "the refusal must not have created anything");

    }

    @Test
    @DisplayName("A non-administrator holding every scope is still refused")
    void aNonAdministratorIsRefused() throws Exception {

        final String regularKey = seedKey(seedUser("provision-user-", "user"), ApiKeyScope.all());
        final String username = newUsername();

        final HttpResponse<String> refused = post("/api/users", regularKey,
                "{\"username\":\"" + username + "\"}");

        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("administrator"), refused.body());
        assertNull(userService.findByUsername(username), "the refusal must not have created anything");

    }

    @Test
    @DisplayName("A key cannot mint one wider than itself")
    void cannotMintAKeyWiderThanTheCallingKey() throws Exception {

        final String narrowAdminKey = seedKey(adminUserId,
                Set.of(ApiKeyScope.API_KEYS_WRITE.getScope(), ApiKeyScope.REDACT.getScope()));

        final String username = newUsername();
        assertEquals(201, post("/api/users", adminKey,
                "{\"username\":\"" + username + "\"}").statusCode());

        final HttpResponse<String> refused = post("/api/users/" + username + "/api-keys", narrowAdminKey,
                "{\"scopes\":[\"redact\",\"ledger:export\"]}");

        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("ledger:export"), refused.body());

        // What it could grant, it still can: the refusal is about the scope, not the endpoint.
        final HttpResponse<String> allowed = post("/api/users/" + username + "/api-keys", narrowAdminKey,
                "{\"scopes\":[\"redact\"]}");
        assertEquals(201, allowed.statusCode(), allowed.body());
        assertFalse(allowed.body().contains("ledger:export"), allowed.body());

    }


    @Test
    @DisplayName("Every user response carries the user's id")
    void userResponsesCarryTheId() throws Exception {

        final String username = newUsername();
        final HttpResponse<String> created = post("/api/users", adminKey,
                "{\"username\":\"" + username + "\",\"role\":\"user\"}");
        assertEquals(201, created.statusCode(), created.body());
        final String id = userService.findByUsername(username).getId().toHexString();
        assertEquals(id, gson.fromJson(created.body(), JsonObject.class).get("id").getAsString());

        assertEquals(id, gson.fromJson(get("/api/users/" + username, adminKey).body(), JsonObject.class)
                .get("id").getAsString());

        final JsonObject me = gson.fromJson(get("/api/users/me", adminKey).body(), JsonObject.class);
        assertEquals(adminUserId.toHexString(), me.get("id").getAsString());

        final JsonObject page = gson.fromJson(get("/api/users?limit=100", adminKey).body(), JsonObject.class);
        assertTrue(page.getAsJsonArray("users").size() > 0);
        page.getAsJsonArray("users").forEach(user -> assertTrue(
                user.getAsJsonObject().get("id").getAsString().matches("[0-9a-f]{24}"), user.toString()));

    }

}
