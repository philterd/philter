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

import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.config.LedgerDeletionConfig;
import ai.philterd.philter.data.services.AdminSettingsDataService;
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

/** The admin settings over real HTTP, including that a setting changed here takes effect. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class SettingsApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private AdminSettingsDataService adminSettingsDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private ObjectId adminId;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        adminId = seedUser("admin");
        adminKey = apiKeyDataService.createApiKey("req", adminId, "test", ApiKeyScope.all()).getMessage();
        // PhEye is not running here, so use a policy needing only the built-in SSN filter.
        assertTrue(policyDataService.create("req", adminId, """
                { "identifiers": { "ssn": { "ssnFilterStrategies": [ { "strategy": "REDACT" } ] } } }
                """, "desc", "notes", "ssn-only", "test").isSuccessful());
    }

    @AfterEach
    void tearDown() {
        // The settings are global to the shared application; put back the defaults other tests expect.
        adminSettingsDataService.update(new AdminSettingsDataService.Update(false, false, "", false, "", "", "", "", false, false),
                adminId, null);
        httpClient.close();
    }

    private ObjectId seedUser(final String role) {
        final String username = "settings-" + role + "-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", username, null, role, policyDataService, contextDataService, "test").isSuccessful());
        return userService.findByUsername(username).getId();
    }

    private HttpResponse<String> send(final String method, final String path, final String key, final String body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + key);
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> filter() throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only"))
                .header("Authorization", "Bearer " + adminKey).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789.")).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("Turning output signing on over the API signs the next redaction; turning it off stops it")
    void signingTakesEffect() throws Exception {
        assertEquals(200, send("PATCH", "/api/settings", adminKey, "{\"signingEnabled\":true}").statusCode());
        assertTrue(filter().headers().firstValue("X-Philter-Signature").isPresent());

        assertEquals(200, send("PATCH", "/api/settings", adminKey, "{\"signingEnabled\":false}").statusCode());
        assertFalse(filter().headers().firstValue("X-Philter-Signature").isPresent());
    }

    @Test
    @DisplayName("Reading reports whether a Phield key is set and never the key; a change is audited by name")
    void readsAndAudits() throws Exception {
        final HttpResponse<String> changed = send("PATCH", "/api/settings", adminKey,
                "{\"phieldEnabled\":true,\"phieldUrl\":\"http://phield.example.com\",\"phieldApiKey\":\"phield-secret\"}");
        assertEquals(200, changed.statusCode(), changed.body());
        assertTrue(changed.body().contains("in the clear"), "an http URL with a key warns: " + changed.body());

        final HttpResponse<String> read = send("GET", "/api/settings", adminKey, null);
        assertEquals(200, read.statusCode(), read.body());
        final JsonObject settings = gson.fromJson(read.body(), JsonObject.class);
        assertTrue(settings.get("phieldApiKeySet").getAsBoolean());
        assertEquals("http://phield.example.com", settings.get("phieldUrl").getAsString());
        assertFalse(read.body().contains("phield-secret"), "the key is never returned");

        final HttpResponse<String> audit = send("GET", "/api/audit?event=settings_updated&limit=100", adminKey, null);
        final String keyId = apiKeyDataService.findOneByApiKey(adminKey).getId().toHexString();
        assertTrue(audit.body().contains("settings: phield_enabled, phield_url, phield_api_key, phield_api_key_key, api_key: " + keyId),
                audit.body());
        assertFalse(audit.body().contains("phield-secret") || audit.body().contains("phield.example.com"),
                "the audit records names, not values");
    }

    @Test
    @DisplayName("An invalid value is a 400 with the reason, and nothing changes")
    void refusesInvalidValues() throws Exception {
        final HttpResponse<String> refused = send("PATCH", "/api/settings", adminKey,
                "{\"diffuseCountsEnabled\":true,\"webhookAllowlist\":\"hooks.example.com, 10.0.0.0/99\"}");
        assertEquals(400, refused.statusCode());
        assertTrue(refused.body().contains("10.0.0.0/99"), refused.body());
        assertFalse(gson.fromJson(send("GET", "/api/settings", adminKey, null).body(), JsonObject.class)
                .get("diffuseCountsEnabled").getAsBoolean(), "nothing was changed");
    }

    @Test
    @DisplayName("Only an administrator holding the scope may read or change settings")
    void refusals() throws Exception {
        final String userKey = apiKeyDataService.createApiKey("req", seedUser("user"), "test", ApiKeyScope.all()).getMessage();
        final HttpResponse<String> notAdmin = send("PATCH", "/api/settings", userKey, "{\"signingEnabled\":true}");
        assertEquals(403, notAdmin.statusCode());
        assertTrue(notAdmin.body().contains("administrator"), notAdmin.body());
        assertEquals(403, send("GET", "/api/settings", userKey, null).statusCode());

        final String readOnly = apiKeyDataService.createApiKey("req", adminId, "test", Set.of("settings:read")).getMessage();
        assertEquals(200, send("GET", "/api/settings", readOnly, null).statusCode());
        final HttpResponse<String> noScope = send("PATCH", "/api/settings", readOnly, "{\"signingEnabled\":true}");
        assertEquals(403, noScope.statusCode());
        assertTrue(noScope.body().contains("settings:write"), noScope.body());
    }

    @Test
    @DisplayName("Reading reports the deployment flags, which a change cannot set")
    void reportsTheDeploymentFlags() throws Exception {
        try {
            AdminAccessConfig.setOverrideForTesting(true);
            LedgerDeletionConfig.setOverrideForTesting(false);
            JsonObject settings = gson.fromJson(send("GET", "/api/settings", adminKey, null).body(), JsonObject.class);
            assertTrue(settings.get("crossUserAccessEnabled").getAsBoolean());
            assertFalse(settings.get("ledgerDeletionEnabled").getAsBoolean());
            // No PHILTER_SIGNING_KEY_PATH in tests, so the key is Philter's own.
            assertFalse(settings.get("signingKeyExternallyManaged").getAsBoolean());

            AdminAccessConfig.setOverrideForTesting(false);
            LedgerDeletionConfig.setOverrideForTesting(true);
            settings = gson.fromJson(send("GET", "/api/settings", adminKey, null).body(), JsonObject.class);
            assertFalse(settings.get("crossUserAccessEnabled").getAsBoolean());
            assertTrue(settings.get("ledgerDeletionEnabled").getAsBoolean());

            // Sent back in a change, as a client that saves what it read would, they are ignored.
            final HttpResponse<String> changed = send("PATCH", "/api/settings", adminKey,
                    "{\"crossUserAccessEnabled\":true,\"ledgerDeletionEnabled\":false,\"signingKeyExternallyManaged\":true,\"diffuseCountsEnabled\":true}");
            assertEquals(200, changed.statusCode(), changed.body());
            settings = gson.fromJson(changed.body(), JsonObject.class);
            assertFalse(settings.get("crossUserAccessEnabled").getAsBoolean());
            assertTrue(settings.get("ledgerDeletionEnabled").getAsBoolean());
            assertFalse(settings.get("signingKeyExternallyManaged").getAsBoolean());
            assertTrue(settings.get("diffuseCountsEnabled").getAsBoolean(), "the rest of the change applies");
        } finally {
            AdminAccessConfig.setOverrideForTesting(null);
            LedgerDeletionConfig.setOverrideForTesting(null);
        }
    }

    @Test
    @DisplayName("Any user reads whether MFA is available and required from their own account")
    void anyUserReadsTheMfaSettings() throws Exception {
        final String userKey = apiKeyDataService.createApiKey("req", seedUser("user"), "test", ApiKeyScope.all()).getMessage();

        JsonObject me = gson.fromJson(send("GET", "/api/users/me", userKey, null).body(), JsonObject.class);
        assertFalse(me.get("mfaAvailable").getAsBoolean());
        assertFalse(me.get("mfaRequired").getAsBoolean());

        assertEquals(200, send("PATCH", "/api/settings", adminKey, "{\"mfaAvailable\":true}").statusCode());
        me = gson.fromJson(send("GET", "/api/users/me", userKey, null).body(), JsonObject.class);
        assertTrue(me.get("mfaAvailable").getAsBoolean());
        assertFalse(me.get("mfaRequired").getAsBoolean());

        assertEquals(200, send("PATCH", "/api/settings", adminKey, "{\"mfaRequired\":true}").statusCode());
        for (final String key : new String[]{userKey, adminKey}) {
            me = gson.fromJson(send("GET", "/api/users/me", key, null).body(), JsonObject.class);
            assertTrue(me.get("mfaAvailable").getAsBoolean());
            assertTrue(me.get("mfaRequired").getAsBoolean());
        }

        // Another user's record, read by an administrator, does not repeat the deployment's settings.
        final String username = me.get("username").getAsString();
        assertFalse(send("GET", "/api/users/" + username, adminKey, null).body().contains("mfaAvailable"));
    }

}
