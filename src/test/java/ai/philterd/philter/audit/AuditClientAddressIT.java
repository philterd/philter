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
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.services.filtering.RedactionWorker;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client address on audit events: every event a request causes records that request's address,
 * resolved through a trusted proxy's {@code X-Forwarded-For} as the request's own handling resolves it,
 * including events the data services and the asynchronous redaction worker record. Events with no
 * request behind them record no address, and the field never holds anything but an address.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class AuditClientAddressIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String POLICY = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private RedactionWorker redactionWorker;
    @Autowired private MongoClient mongoClient;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String adminKey;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String admin = "audit-address-" + UUID.randomUUID();
        assertTrue(userService.createUser("req", admin, "admin", policyDataService, contextDataService, "test").isSuccessful());
        adminKey = apiKeyDataService.createApiKey("req", userService.findByUsername(admin).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    /** A request from a client at {@code clientAddress}, as the local proxy the test runs behind reports it. */
    private HttpResponse<String> send(final String method, final String path, final String contentType,
                                      final HttpRequest.BodyPublisher body, final String clientAddress) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + adminKey)
                .header("X-Forwarded-For", clientAddress)
                .method(method, body);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(final String method, final String path, final String json,
                                          final String clientAddress) throws Exception {
        return send(method, path, "application/json", HttpRequest.BodyPublishers.ofString(json), clientAddress);
    }

    /** A rule-based policy, so a redaction needs no model service. */
    private String ssnPolicy() throws Exception {
        final String name = "ssn-" + UUID.randomUUID();
        assertEquals(201, sendJson("POST", "/api/policies?name=" + name, POLICY, "198.51.100.1").statusCode());
        return name;
    }

    private MongoCollection<Document> events() {
        return mongoClient.getDatabase("philter").getCollection("audit_events");
    }

    /** The stored events matching the filter, failing if there are none. */
    private List<Document> recorded(final Bson filter) {
        final List<Document> found = events().find(filter).into(new ArrayList<>());
        assertFalse(found.isEmpty(), "no event matches " + filter);
        return found;
    }

    private static void assertFromTheApi(final String clientAddress, final List<Document> events) {
        for (final Document event : events) {
            assertEquals(clientAddress, event.getString("client_ip_address"), event.toJson());
            assertEquals("api", event.getString("source"), event.toJson());
        }
    }

    @Test
    @DisplayName("Creating a user records the administrator's client address")
    void userAdministration() throws Exception {
        final String username = "audited-" + UUID.randomUUID();
        assertEquals(201, sendJson("POST", "/api/users",
                "{\"username\":\"" + username + "\",\"password\":\"a-long-enough-password-1\"}", "203.0.113.11").statusCode());
        final ObjectId userId = userService.findByUsername(username).getId();

        assertFromTheApi("203.0.113.11", recorded(Filters.and(Filters.eq("associated_object", userId),
                Filters.in("event", "user_created", "user_password_set"))));
        assertEquals(2, recorded(Filters.eq("associated_object", userId)).size());
    }

    @Test
    @DisplayName("Changing a setting records the client address")
    void settingsChange() throws Exception {
        try {
            assertEquals(200, sendJson("PATCH", "/api/settings", "{\"mfaAvailable\":true}", "203.0.113.12").statusCode());
        } finally {
            assertEquals(200, sendJson("PATCH", "/api/settings", "{\"mfaAvailable\":false}", "203.0.113.12").statusCode());
        }
        assertFromTheApi("203.0.113.12", recorded(Filters.and(Filters.eq("event", "settings_updated"),
                Filters.eq("client_ip_address", "203.0.113.12"))));
        assertTrue(events().countDocuments(Filters.and(Filters.eq("event", "settings_updated"),
                Filters.ne("client_ip_address", "203.0.113.12"), Filters.regex("details", "mfa_available"))) == 0,
                "no settings change is recorded without the address");
    }

    @Test
    @DisplayName("Creating a policy records the client address on each event it causes")
    void policyChange() throws Exception {
        final String name = "audited-" + UUID.randomUUID();
        assertEquals(201, sendJson("POST", "/api/policies?name=" + name, POLICY, "2001:db8::13").statusCode());

        final List<Document> events = recorded(Filters.and(Filters.in("event", "policy_created", "policy_activated"),
                Filters.eq("client_ip_address", "2001:db8::13")));
        assertFromTheApi("2001:db8::13", events);
        assertTrue(events.stream().anyMatch(event -> "policy_created".equals(event.getString("event"))), events.toString());
    }

    @Test
    @DisplayName("A synchronous redaction records the client address")
    void synchronousRedaction() throws Exception {
        final HttpResponse<String> redacted = send("POST", "/api/filter?p=" + ssnPolicy(), "text/plain",
                HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."), "203.0.113.14");
        assertEquals(200, redacted.statusCode(), redacted.body());

        assertFromTheApi("203.0.113.14", recorded(Filters.and(Filters.eq("event", "document_redaction_completed"),
                Filters.eq("client_ip_address", "203.0.113.14"))));
    }

    @Test
    @DisplayName("An asynchronous redaction records the submitting request's address, including on the worker's events")
    void asynchronousRedaction() throws Exception {
        final HttpResponse<String> accepted = send("POST", "/api/filter?p=" + ssnPolicy(), "application/pdf",
                HttpRequest.BodyPublishers.ofByteArray(onePagePdf()), "203.0.113.15");
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();

        assertFromTheApi("203.0.113.15", recorded(Filters.and(Filters.eq("request_id", documentId),
                Filters.eq("event", "document_redaction_initiated"))));

        // The worker's thread serves no request; it records the address the job was submitted from.
        final Bson completed = Filters.and(Filters.eq("request_id", documentId),
                Filters.eq("event", "document_redaction_completed"));
        for (int attempt = 0; attempt < 100 && events().countDocuments(completed) == 0; attempt++) {
            redactionWorker.poll();
            Thread.sleep(100);
        }
        assertFromTheApi("203.0.113.15", recorded(completed));
    }

    @Test
    @DisplayName("An event with no request behind it records no address and says it came from the system")
    void systemEvent() {
        // The administrator Philter creates at startup.
        final ObjectId admin = userService.findAnyByUsername("admin").getId();
        for (final Document event : recorded(Filters.and(Filters.eq("event", "user_created"),
                Filters.eq("associated_object", admin)))) {
            assertNull(event.get("client_ip_address"), event.toJson());
            assertEquals("system", event.getString("source"), event.toJson());
        }
    }

    @Test
    @DisplayName("The address field holds only addresses, and the audit API returns the source")
    void theAddressFieldHoldsOnlyAddresses() throws Exception {
        assertEquals(201, sendJson("POST", "/api/policies?name=audited-" + UUID.randomUUID(), POLICY, "203.0.113.16").statusCode());

        for (final Document event : events().find()) {
            final String address = event.getString("client_ip_address");
            assertTrue(address == null || ClientAddress.isAddress(address), event.toJson());
            assertTrue(List.of("api", "system", "test").contains(event.getString("source")), event.toJson());
        }

        final HttpResponse<String> listed = send("GET", "/api/audit?limit=100&event=policy_created", null,
                HttpRequest.BodyPublishers.noBody(), "203.0.113.17");
        assertEquals(200, listed.statusCode(), listed.body());
        boolean found = false;
        for (final JsonElement element : gson.fromJson(listed.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject event = element.getAsJsonObject();
            if (event.has("clientIpAddress") && "203.0.113.16".equals(event.get("clientIpAddress").getAsString())) {
                assertEquals("api", event.get("source").getAsString());
                found = true;
            }
        }
        assertTrue(found, listed.body());
    }

    /** A real, parseable one-page PDF, so content-type detection sees genuine PDF bytes. */
    private static byte[] onePagePdf() throws Exception {
        try (final PDDocument document = new PDDocument();
             final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final PDPage page = new PDPage();
            document.addPage(page);
            try (final PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("His SSN was 123-45-6789.");
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

}
