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
import java.util.LinkedHashSet;
import java.util.Set;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.model.Constants;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
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
import org.springframework.core.env.Environment;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the API request filter chain.
 *
 * <p>The controller unit tests use MockMvc standalone setup, which builds a bare dispatcher around a
 * single controller and never runs the servlet filters. The filters have their own unit tests, but
 * nothing else verifies that they are registered, ordered, and composed with the controllers when a
 * real request arrives. That composition is the API's security boundary: authentication, the IP
 * request size limits, content-type verification, and admin cross-user access.
 *
 * <p>This boots the whole application on a random port against an in-process, in-memory MongoDB (no
 * Docker, no external services, as in {@code OpenApiExportIT}) and drives it over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ApiFilterChainIT {

    /**
     * Registered as a nested class rather than imported directly: a nested {@code @TestConfiguration}
     * is processed after the application's own configuration, so its beans override the real ones.
     * An {@code @Import} of the same class is processed too early and the application's beans win,
     * which surfaces as PHILTER_ENCRYPTION_KEY being required.
     */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

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
    private String username;
    private String otherUsername;


    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        // Each test gets its own user so state cannot leak between tests through the shared context.
        username = "filter-chain-" + UUID.randomUUID() + "@example.com";
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
    @DisplayName("A redact-lists body at the documented maximum is accepted")
    void aFullSizeRedactListsBodyIsAccepted() throws Exception {

        // One POST replaces both lists: 2 x 1000 terms of 100 characters, ~203 KB. The limit was 10 KB.
        final String term = "\"" + "b".repeat(RedactListsDataService.MAXIMUM_TERM_LENGTH) + "\"";
        final String list = String.join(",", java.util.Collections.nCopies(
                RedactListsDataService.MAXIMUM_TERMS_PER_LIST, term));
        final String body = "{\"alwaysRedact\": [" + list + "], \"neverRedact\": [" + list + "]}";

        assertTrue(body.length() > 200_000, "the documented maximum really is this large: " + body.length());

        final HttpResponse<String> response = send(authenticated(baseUrl + "/api/redact-lists")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());

        assertEquals(200, response.statusCode(),
                "the documented maximum must fit the configuration limit: " + response.body());

    }

    private HttpResponse<String> savePolicy(final String name, final String json) throws Exception {
        return send(authenticated(baseUrl + "/api/policies?name=" + name)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Test
    @DisplayName("Saving a policy twice overwrites it rather than creating a duplicate")
    void savingAPolicyTwiceOverwritesIt() throws Exception {

        final String name = "upsert-" + System.nanoTime();

        assertEquals(201, savePolicy(name, SSN_POLICY).statusCode());
        assertEquals(201, savePolicy(name, SSN_POLICY).statusCode(), "the endpoint is documented as an upsert");

        // The raw save path inserted a second document under the same name.
        final HttpResponse<String> list = send(authenticated(baseUrl + "/api/policies").GET().build());
        final long occurrences = list.body().split("\"" + name + "\"", -1).length - 1;
        assertEquals(1, occurrences, "exactly one policy may carry the name: " + list.body());

    }

    @Test
    @DisplayName("A saved policy is retained as a version snapshot")
    void aSavedPolicyIsRetainedAsAVersionSnapshot() throws Exception {

        final String name = "snap-" + System.nanoTime();
        assertEquals(201, savePolicy(name, SSN_POLICY).statusCode());

        final HttpResponse<String> versions =
                send(authenticated(baseUrl + "/api/policies/" + name + "/versions").GET().build());

        assertEquals(200, versions.statusCode());
        assertTrue(versions.body().contains("\"revision\""),
                "the save must retain a snapshot, which the raw insert did not: " + versions.body());

    }

    @Test
    @DisplayName("An invalid policy name is rejected")
    void anInvalidPolicyNameIsRejected() throws Exception {

        assertEquals(400, savePolicy("has%20spaces", SSN_POLICY).statusCode(),
                "the name rules must apply on the API path too");
        assertEquals(400, savePolicy("managed_sneaky", SSN_POLICY).statusCode(),
                "a name may not impersonate a managed policy");

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

        for (final String path : new String[]{"/api/health", "/api/signing-key"}) {

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

    /** Mints an additional key for the same user carrying only the given scopes. */
    private String scopedKey(final ApiKeyScope... scopes) {
        final ObjectId userId = userService.findByUsername(username).getId();
        final Set<String> granted = new LinkedHashSet<>();
        for (final ApiKeyScope scope : scopes) {
            granted.add(scope.getScope());
        }
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", granted);
        assertTrue(response.isSuccessful(), "the scoped key must be created");
        return response.getMessage();
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

    @Test
    @DisplayName("A key without the endpoint's scope is refused with 403")
    void aKeyMissingTheScopeIsForbidden() throws Exception {

        final String redactOnly = scopedKey(ApiKeyScope.REDACT);

        // In scope: the key may redact.
        final HttpResponse<String> allowed = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only"))
                .header("Authorization", "Bearer " + redactOnly)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build());

        assertEquals(200, allowed.statusCode());

        // Out of scope: the same key may not read policies.
        final HttpResponse<String> refused = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/policies"))
                .header("Authorization", "Bearer " + redactOnly)
                .GET()
                .build());

        // 403, not 404: the caller holds a valid credential for their own data, so there is nothing to
        // conceal, and naming the missing scope is actionable.
        assertEquals(403, refused.statusCode());
        assertTrue(refused.body().contains("policies:read"),
                "the refusal must name the missing scope: " + refused.body());

    }

    @Test
    @DisplayName("Ledger export is a separate scope from ledger read")
    void ledgerExportIsSeparateFromLedgerRead() throws Exception {

        final String readOnly = scopedKey(ApiKeyScope.LEDGER_READ);

        // Listing chains is permitted.
        final HttpResponse<String> list = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/ledger"))
                .header("Authorization", "Bearer " + readOnly)
                .GET()
                .build());

        assertEquals(200, list.statusCode());

        // Exporting one, which would return the original tokens in the clear, is not. This is the
        // separation that motivated splitting ledger:export out of ledger:read.
        final HttpResponse<String> export = send(HttpRequest.newBuilder(
                URI.create(baseUrl + "/api/ledger/any-document-id/export"))
                .header("Authorization", "Bearer " + readOnly)
                .GET()
                .build());

        assertEquals(403, export.statusCode());
        assertTrue(export.body().contains("ledger:export"),
                "the refusal must name the missing scope: " + export.body());

    }

    @Test
    @DisplayName("A key with no scopes can call nothing")
    void aKeyWithNoScopesCanCallNothing() throws Exception {

        final String noScopes = scopedKey();

        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/policies"))
                .header("Authorization", "Bearer " + noScopes)
                .GET()
                .build());

        assertEquals(403, response.statusCode());

    }

    @Test
    @DisplayName("Scopes do not bypass the unauthenticated endpoints")
    void scopelessKeyStillReachesUnauthenticatedEndpoints() throws Exception {

        // /api/health carries no scope requirement because it takes no credential at all. A key with
        // no scopes must not be blocked from it, or the interceptor is over-reaching.
        final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/health"))
                .header("Authorization", "Bearer " + scopedKey())
                .GET()
                .build());

        assertEquals(200, response.statusCode());

    }

    @Test
    @DisplayName("A path that resolves to a protected endpoint cannot borrow an unauthenticated prefix")
    void unauthenticatedPrefixesCannotBeUsedToReachProtectedEndpoints() throws Exception {

        // The interceptor skips the scope check for the unauthenticated paths by matching the request
        // URI. If a crafted URI could match one of those prefixes while still routing to a protected
        // handler, it would bypass the scope check entirely. Each of these resolves toward
        // /api/policies, which a scopeless key must never be able to read.
        final String noScopes = scopedKey();

        for (final String attempt : new String[]{
                "/api/signing-key/../policies",
                "/api/health/../policies",
                "/api/policies;jsessionid=x"}) {

            final HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + attempt))
                    .header("Authorization", "Bearer " + noScopes)
                    .GET()
                    .build());

            assertNotEquals(200, response.statusCode(),
                    attempt + " must not reach a protected handler; got " + response.statusCode());
            assertFalse(response.body().contains("ssn-only"),
                    attempt + " must not return policy data: " + response.body());

        }

    }

}
