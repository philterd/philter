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
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@code GET /api/audit} over real HTTP against the whole application, so the scope check, the
 * admin check, and the audit of the read itself are exercised as a deployment would meet them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class AuditApiIT {

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

        AdminAccessConfig.setOverrideForTesting(true);

        adminUserId = userId(createUser("audit-admin-", "admin"));
        adminKey = createKey(adminUserId, ApiKeyScope.all());

    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    /** Creates a user and returns its username. */
    private String createUser(final String prefix, final String role) {
        final String username = prefix + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return username;
    }

    private ObjectId userId(final String username) {
        return userService.findByUsername(username).getId();
    }

    private String createKey(final ObjectId userId, final Set<String> scopes) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        return response.getMessage();
    }

    private HttpResponse<String> get(final String url, final String apiKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A key without audit:read is refused with 403")
    void aKeyWithoutTheScopeIsRefused() throws Exception {

        final String redactOnly = createKey(adminUserId, Set.of(ApiKeyScope.REDACT.getScope()));

        final HttpResponse<String> response = get(baseUrl + "/api/audit", redactOnly);

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("audit:read"),
                "the refusal should name the missing scope: " + response.body());

    }

    @Test
    @DisplayName("A non-administrator holding audit:read is still refused with 403")
    void aNonAdministratorIsRefused() throws Exception {

        final String regularKey = createKey(userId(createUser("audit-user-", "user")), ApiKeyScope.all());

        final HttpResponse<String> response = get(baseUrl + "/api/audit", regularKey);

        assertEquals(403, response.statusCode());

    }

    @Test
    @DisplayName("An administrator reads the log, and the read is itself audited")
    void anAdministratorReadsTheLogAndTheReadIsAudited() throws Exception {

        final HttpResponse<String> response = get(baseUrl + "/api/audit?limit=50", adminKey);

        assertEquals(200, response.statusCode());

        final JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(body.getAsJsonArray("events").size() > 0, "provisioning the user and key is audited");
        assertTrue(body.get("total").getAsLong() > 0, "total must count the matching events");

        // Keyed to this test's reader: the tests share a database, so other reads are recorded too.
        final Document recorded = mongoClient.getDatabase("philter").getCollection("audit_events")
                .find(new Document("event", "audit_log_retrieved").append("api_key_id", adminUserId)).first();

        assertNotNull(recorded, "reading the audit log must itself be audited, naming the reader");
        assertNotNull(recorded.getString("client_ip_address"));

    }

    @Test
    @DisplayName("Events can be filtered by type and by time range")
    void eventsCanBeFilteredByTypeAndTimeRange() throws Exception {

        final HttpResponse<String> byType = get(baseUrl + "/api/audit?event=user_created", adminKey);
        assertEquals(200, byType.statusCode());

        final JsonObject typed = gson.fromJson(byType.body(), JsonObject.class);
        assertTrue(typed.getAsJsonArray("events").size() > 0, "the test users were created");
        typed.getAsJsonArray("events").forEach(event ->
                assertEquals("user_created", event.getAsJsonObject().get("event").getAsString()));

        // A window that closed before this test ran cannot contain its events.
        final HttpResponse<String> byTime =
                get(baseUrl + "/api/audit?from=2000-01-01T00:00:00Z&to=2001-01-01T00:00:00Z", adminKey);
        assertEquals(200, byTime.statusCode());
        assertEquals(0, gson.fromJson(byTime.body(), JsonObject.class).get("total").getAsLong());

    }

    @Test
    @DisplayName("owner narrows the listing to one principal")
    void ownerNarrowsTheListingToOnePrincipal() throws Exception {

        final String otherUsername = createUser("audit-user-", "user");
        final String otherUserId = userId(otherUsername).toString();

        final HttpResponse<String> response = get(baseUrl + "/api/audit?owner=" + otherUsername, adminKey);

        assertEquals(200, response.statusCode());

        final JsonObject body = gson.fromJson(response.body(), JsonObject.class);
        assertTrue(body.getAsJsonArray("events").size() > 0, "creating the user is audited against it");
        body.getAsJsonArray("events").forEach(event -> assertEquals(otherUserId,
                event.getAsJsonObject().get("apiKeyId").getAsString(),
                "every event returned must belong to the named principal"));

    }

    @Test
    @DisplayName("An owner that does not exist is a 404, not an empty page")
    void anUnknownOwnerIsNotFound() throws Exception {
        assertEquals(404, get(baseUrl + "/api/audit?owner=nobody@example.com", adminKey).statusCode());
    }

    @Test
    @DisplayName("An event type Philter does not emit is rejected")
    void anUnknownEventTypeIsRejected() throws Exception {
        assertEquals(400, get(baseUrl + "/api/audit?event=not_an_event", adminKey).statusCode());
    }

}
