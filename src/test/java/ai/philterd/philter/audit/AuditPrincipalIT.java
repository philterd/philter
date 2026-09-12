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
package ai.philterd.philter.audit;

import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives authenticated HTTP requests against the whole application (in-memory MongoDB, as in
 * {@code ApiFilterChainIT}) and asserts over every audit event they produced. Policy deletion,
 * rollback, and version history reads were written with a hard-coded null principal and the
 * description in the client IP field.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class AuditPrincipalIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String POLICY_NAME = "audit-principal-policy";

    private static final String POLICY_JSON =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

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

    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private ObjectId userId;

    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        final String username = "audit-principal-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");

        userId = userService.findByUsername(username).getId();

        final ServiceResponse keyResponse = apiKeyDataService.createApiKey("req", userId, "test");
        assertTrue(keyResponse.isSuccessful(), "the API key must be created");
        apiKey = keyResponse.getMessage();

        // Provisioning is itself audited. Clear it so only the test's HTTP requests are asserted on.
        auditEvents().deleteMany(new Document());

    }

    @Test
    @DisplayName("Every audit event written by an authenticated API call names the acting principal")
    void everyAuditEventFromAnAuthenticatedCallNamesThePrincipal() throws Exception {

        exerciseAuthenticatedEndpoints();

        final List<Document> events = auditEvents().find().into(new ArrayList<>());
        assertFalse(events.isEmpty(), "the API calls must have produced audit events");

        final List<String> anonymous = events.stream()
                .filter(event -> event.get("api_key_id") == null)
                .map(event -> event.getString("event"))
                .distinct()
                .toList();

        assertEquals(List.of(), anonymous,
                "these events were written by an authenticated API call but name no principal, so the "
                        + "audit log cannot say who performed the action");

    }

    @Test
    @DisplayName("Policy deletion, rollback, and history reads record the caller, the client IP, and the policy")
    void thePolicyEventsRecordTheCallerTheClientIpAndThePolicy() throws Exception {

        exerciseAuthenticatedEndpoints();

        for (final AuditLogEvent event : List.of(AuditLogEvent.POLICY_DELETED,
                AuditLogEvent.POLICY_ROLLED_BACK, AuditLogEvent.POLICY_VERSION_HISTORY_RETRIEVED)) {

            final Document recorded = auditEvents()
                    .find(new Document("event", event.getAuditLogEvent())).first();

            assertNotNull(recorded, event.getAuditLogEvent() + " must have been recorded");

            assertEquals(userId, recorded.get("api_key_id"),
                    event.getAuditLogEvent() + " must name the caller as the acting principal");

            // Loopback, since the request came from this JVM. It used to hold the description.
            final String clientIpAddress = recorded.getString("client_ip_address");
            assertNotNull(clientIpAddress, event.getAuditLogEvent() + " must record the client IP address");
            assertTrue(clientIpAddress.matches("[0-9a-fA-F:.]+"),
                    event.getAuditLogEvent() + " recorded '" + clientIpAddress + "' as the client IP address");

            final String details = recorded.getString("details");
            assertTrue((details != null && details.contains(POLICY_NAME)) || recorded.get("associated_object") != null,
                    event.getAuditLogEvent() + " must identify the policy it concerned");

        }

    }

    /** A policy saved through the API is snapshotted at revision 0, so it rolls back immediately. */
    private void exerciseAuthenticatedEndpoints() throws Exception {

        assertEquals(201, send(authenticated(baseUrl + "/api/policies?name=" + POLICY_NAME)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(POLICY_JSON))
                .build()).statusCode(), "the policy must be saved");

        assertEquals(200, send(authenticated(baseUrl + "/api/policies/" + POLICY_NAME + "/versions")
                .GET().build()).statusCode(), "the version history must be readable");

        assertEquals(201, send(authenticated(baseUrl + "/api/policies/" + POLICY_NAME + "/rollback?revision=0")
                .POST(HttpRequest.BodyPublishers.noBody()).build()).statusCode(),
                "the policy must be rolled back");

        assertEquals(200, send(authenticated(baseUrl + "/api/contexts").GET().build()).statusCode());

        assertEquals(200, send(authenticated(baseUrl + "/api/redact-lists").GET().build()).statusCode());

        assertEquals(200, send(authenticated(baseUrl + "/api/policies/" + POLICY_NAME)
                .DELETE().build()).statusCode(), "the policy must be deleted");

    }

    private MongoCollection<Document> auditEvents() {
        return mongoClient.getDatabase("philter").getCollection("audit_events");
    }

    private HttpRequest.Builder authenticated(final String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

}
