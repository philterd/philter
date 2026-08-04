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

import ai.philterd.philter.api.filters.auth.ApiAuthenticationFilter;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Constants;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the API request filter chain.
 *
 * <p>The controller unit tests use MockMvc standalone setup, which builds a bare dispatcher around a
 * single controller and never runs the servlet filters. The filters have their own unit tests, but
 * nothing else verifies that they are registered, ordered, and composed with the controllers when a
 * real request arrives. That composition is the API's security boundary: authentication, the IP
 * allowlist, request size limits, content-type verification, and admin cross-user access.
 *
 * <p>This boots the whole application on a random port against an in-process, in-memory MongoDB (no
 * Docker, no external services, as in {@code OpenApiExportIT}) and drives it over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ApiFilterChainIT {

    /** A syntactically valid key (sk_ + 32 alphanumerics) that was never issued. */
    private static final String UNISSUED_API_KEY = "sk_00000000000000000000000000000000";

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

    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private String otherUsername;

    /**
     * Replaces the application's {@code mongoClient} bean with one backed by an in-process, in-memory
     * mongo-java-server, and the encryption service with a test implementation, so the application
     * boots with no external dependencies and without PHILTER_ENCRYPTION_KEY.
     */
    @TestConfiguration
    static class InMemoryMongoConfiguration {

        @Bean(destroyMethod = "shutdown")
        MongoServer mongoServer() {
            return new MongoServer(new MemoryBackend());
        }

        @Bean
        MongoClient mongoClient(final MongoServer mongoServer) {
            final InetSocketAddress address = mongoServer.bind();
            return MongoClients.create("mongodb://" + address.getHostName() + ":" + address.getPort());
        }

        @Bean
        EncryptionService encryptionService() {
            return new TestEncryptionService();
        }

    }

    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        // Each test gets its own user so state cannot leak between tests through the shared context.
        final String username = "filter-chain-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", "user",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");

        final ObjectId userId = userService.findByUsername(username).getId();
        final ServiceResponse keyResponse = apiKeyDataService.createApiKey("req", userId, "test");
        assertTrue(keyResponse.isSuccessful(), "the API key must be created");
        apiKey = keyResponse.getMessage();

        // A second user, so cross-user access can be attempted against a real account.
        otherUsername = "other-" + UUID.randomUUID() + "@example.com";
        userService.createUser("req", otherUsername, "password", "user",
                policyDataService, contextDataService, "test");

        // The seeded default policy detects persons through ph-eye, which is not running here. Use a
        // policy that needs only the built-in SSN filter.
        final ServiceResponse policy = policyDataService.create("req", userId, """
                { "identifiers": { "ssn": { "ssnFilterStrategies": [ { "strategy": "REDACT" } ] } } }
                """, "desc", "notes", "ssn-only", "test");
        assertTrue(policy.isSuccessful(), "the test policy must be saved");

    }

    @AfterEach
    void tearDown() {
        // Never leave an override set: it is global state that would leak into other tests.
        ApiAuthenticationFilter.setAllowlistOverrideForTesting(null);
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    @Test
    @DisplayName("A request with no Authorization header is rejected with 401")
    void missingCredentialsAreUnauthorized() throws Exception {

        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(401, response.statusCode());

    }

    @Test
    @DisplayName("A malformed API key is rejected with 401")
    void malformedApiKeyIsUnauthorized() throws Exception {

        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only"))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer not-a-valid-key")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(401, response.statusCode());

    }

    @Test
    @DisplayName("A well-formed but unissued API key is rejected with 401")
    void unknownApiKeyIsUnauthorized() throws Exception {

        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only"))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + UNISSUED_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(401, response.statusCode());

    }

    @Test
    @DisplayName("A valid key redacts text through the whole chain")
    void validKeyRedactsText() throws Exception {

        final HttpResponse<String> response = send(authenticated(baseUrl + "/api/filter?p=ssn-only")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(200, response.statusCode());
        assertFalse(response.body().contains("123-45-6789"), "the SSN must not survive redaction");
        assertNotNull(response.headers().firstValue("X-Philter-Policy-Name").orElse(null),
                "the applied policy must be reported");

    }

    @Test
    @DisplayName("A document body over MAX_FILE_SIZE_BYTES is rejected with 413")
    void oversizedDocumentBodyIsRejected() throws Exception {

        final String body = "a".repeat(Constants.MAX_FILE_SIZE_BYTES + 1);

        final HttpResponse<String> response = send(authenticated(baseUrl + "/api/filter?p=ssn-only")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(413, response.statusCode());

    }

    @Test
    @DisplayName("A configuration body over MAX_FILE_SIZE_BYTES_OTHER is rejected with 413")
    void oversizedConfigurationBodyIsRejected() throws Exception {

        // A small body on the same endpoint succeeds, so the rejection below is attributable to the
        // size limit rather than to the endpoint or the payload shape.
        final HttpResponse<String> accepted = send(authenticated(baseUrl + "/api/redact-lists")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"alwaysRedact\": [\"Project Cardinal\"]}"))
                .build());

        assertEquals(200, accepted.statusCode(), "a small configuration body must be accepted");

        // Well past the configuration limit, which is much smaller than the document limit.
        final String terms = "\"" + "b".repeat((int) Constants.MAX_FILE_SIZE_BYTES_OTHER + 1) + "\"";

        final HttpResponse<String> response = send(authenticated(baseUrl + "/api/redact-lists")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"alwaysRedact\": [" + terms + "]}"))
                .build());

        assertEquals(413, response.statusCode());

    }

    @Test
    @DisplayName("A body that contradicts its declared content type is rejected with 415")
    void bodyContradictingItsContentTypeIsRejected() throws Exception {

        final HttpResponse<String> response = send(authenticated(baseUrl + "/api/filter?p=ssn-only")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(onePagePdf()))
                .build());

        assertEquals(415, response.statusCode());

    }

    @Test
    @DisplayName("An address outside API_IP_ALLOWLIST is rejected with 403, one inside is allowed")
    void ipAllowlistIsEnforced() throws Exception {

        ApiAuthenticationFilter.setAllowlistOverrideForTesting("10.0.0.0/8");

        // The test client connects from loopback, which the allowlist above excludes.
        final HttpResponse<String> blocked = send(authenticated(baseUrl + "/api/filter?p=ssn-only")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(403, blocked.statusCode());

        // The same request from an address inside the range is allowed. The filter resolves the client
        // address from X-Forwarded-For when present.
        final HttpResponse<String> allowed = send(authenticated(baseUrl + "/api/filter?p=ssn-only")
                .header("Content-Type", "text/plain")
                .header("X-Forwarded-For", "10.1.2.3")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(200, allowed.statusCode());

    }

    @Test
    @DisplayName("With cross-user access disabled, naming another owner returns 404 rather than 403")
    void crossUserAccessDisabledHidesOtherUsers() throws Exception {

        // Read through the real configuration rather than forcing an override: with
        // ADMIN_CROSS_USER_ACCESS_ENABLED unset, cross-user access is disabled by default, which is
        // the state this test is about. Asserting it also fails loudly if another test leaks an
        // override instead of silently testing the wrong configuration.
        assertFalse(AdminAccessConfig.isCrossUserAccessEnabled(),
                "cross-user access must be disabled by default for this test");

        // The same endpoint without an owner succeeds, so the 404 below is attributable to the owner
        // parameter and not to the endpoint or the credentials.
        final HttpResponse<String> own = send(authenticated(baseUrl + "/api/policies").GET().build());
        assertEquals(200, own.statusCode(), "a user must be able to list their own policies");

        final HttpResponse<String> response = send(authenticated(
                baseUrl + "/api/policies?owner=" + otherUsername).GET().build());

        // 404, never 403: the API must not reveal whether the user or the resource exists.
        assertEquals(404, response.statusCode());
        assertFalse(response.body().contains(otherUsername), "the response must not echo the owner");

    }

    @Test
    @DisplayName("The documented unauthenticated endpoints stay open")
    void unauthenticatedEndpointsRemainOpen() throws Exception {

        for (final String path : new String[]{"/api/status", "/api/health", "/api/signing-key"}) {

            final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .GET()
                    .build());

            assertEquals(200, response.statusCode(), path + " must be served without authentication");

        }

    }

    @Test
    @DisplayName("A PDF submission is accepted for asynchronous redaction with a document id")
    void pdfSubmissionIsAccepted() throws Exception {

        final HttpResponse<String> response = send(authenticated(baseUrl + "/api/filter?p=ssn-only")
                .header("Content-Type", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(onePagePdf()))
                .build());

        assertEquals(202, response.statusCode());
        assertTrue(response.body().contains("documentId"),
                "the response must carry the document id to poll: " + response.body());

    }

    private HttpRequest.Builder authenticated(final String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
