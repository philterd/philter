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

import ai.philterd.philter.config.ProvisioningConfig;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provisioning a deployment over real HTTP: an administrator's key creates a user, mints a key for
 * it, and that key then works, which is the whole point of the endpoints existing. The refusals are
 * exercised here too, because they run in a filter and an interceptor that a controller test does not
 * see the same way.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ProvisioningApiIT {

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

        ProvisioningConfig.setOverrideForTesting(true);

        adminUserId = seedUser("provision-admin-", "admin");
        adminKey = seedKey(adminUserId, ApiKeyScope.all());

    }

    @AfterEach
    void tearDown() {
        ProvisioningConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    /** Seeds a user directly, standing in for the account a deployment starts with. */
    private ObjectId seedUser(final String prefix, final String role) {
        final String username = prefix + UUID.randomUUID();
        final ServiceResponse created = userService.createUser("req", username, PASSWORD, role,
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
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");

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
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}").statusCode());
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
    @DisplayName("With the deployment opted out, neither endpoint is there")
    void neitherEndpointExistsUnlessTheDeploymentOptsIn() throws Exception {

        ProvisioningConfig.setOverrideForTesting(false);

        final String username = newUsername();

        assertEquals(404, post("/api/users", adminKey,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}").statusCode());
        assertEquals(404, post("/api/users/" + username + "/api-keys", adminKey,
                "{\"scopes\":[\"redact\"]}").statusCode());

        assertNull(userService.findByUsername(username), "nothing may be created while the switch is off");

    }

    @Test
    @DisplayName("Without a credential nothing is created, whether the switch is on or off")
    void refusesAnUnauthenticatedCaller() throws Exception {

        final String username = newUsername();
        final String body = "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";

        for (final boolean enabled : new boolean[]{true, false}) {

            ProvisioningConfig.setOverrideForTesting(enabled);

            final HttpResponse<String> anonymous = httpClient.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/api/users"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(), HttpResponse.BodyHandlers.ofString());

            // The gate is the first thing the handler checks, so this proves the credential is
            // demanded ahead of it rather than by it.
            assertEquals(401, anonymous.statusCode(), "enabled=" + enabled + ": " + anonymous.body());
            assertNull(userService.findByUsername(username), "enabled=" + enabled);

        }

    }

    @Test
    @DisplayName("A key without the provisioning scope is refused, and told which scope")
    void refusesAKeyWithoutTheScope() throws Exception {

        final String redactOnly = seedKey(adminUserId, Set.of(ApiKeyScope.REDACT.getScope()));
        final String username = newUsername();

        final HttpResponse<String> refused = post("/api/users", redactOnly,
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");

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
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}");

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
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}").statusCode());

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

}
