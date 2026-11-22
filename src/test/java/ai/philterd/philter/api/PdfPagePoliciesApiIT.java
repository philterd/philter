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
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import ai.philterd.philter.testutil.OfficeDocuments;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PDFs whose page sets are redacted with their own policies, named with page_policy. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PdfPagePoliciesApiIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String PAGE_TEXT = "SSN 123-45-6789 and email jane.doe@example.com.";

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    private static final String EMAIL_POLICY =
            "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();
    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private String context;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        final String username = "pages-" + UUID.randomUUID() + "@example.com";
        assertTrue(userService.createUser("req", username, "admin", policyDataService, contextDataService, "test").isSuccessful());
        apiKey = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
        savePolicy("ssn-only", SSN_POLICY);
        savePolicy("email-only", EMAIL_POLICY);
        context = "pages-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpResponse<String> created = send(authenticated("/api/contexts").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + context + "\",\"ledger\":true}")).build());
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());
    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private HttpRequest.Builder authenticated(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void savePolicy(final String name, final String json) throws Exception {
        final HttpResponse<String> saved = send(authenticated("/api/policies?name=" + name)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, saved.body());
    }

    private static String pagePolicy(final String value) {
        return "&page_policy=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<byte[]> redact(final String query, final String contentType, final byte[] body) throws Exception {
        return httpClient.send(authenticated("/api/filter" + query).header("Content-Type", contentType)
                        .header("Accept", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] pdf(final int pages) throws Exception {
        try (final PDDocument document = new PDDocument(); final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                final PDPage page = new PDPage();
                document.addPage(page);
                try (final PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 720);
                    content.showText(PAGE_TEXT);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    // "type policy" for each redaction in the document's ledger, in order.
    private List<String> ledgerRedactions(final String documentId) throws Exception {
        final HttpResponse<String> ledger = send(authenticated("/api/ledger/" + documentId).GET().build());
        assertEquals(200, ledger.statusCode(), ledger.body());
        final JsonObject chain = gson.fromJson(ledger.body(), JsonObject.class);
        assertTrue(chain.get("hashChainValid").getAsBoolean(), ledger.body());
        final List<String> redactions = new ArrayList<>();
        for (final JsonElement element : chain.getAsJsonArray("entries")) {
            final JsonObject entry = element.getAsJsonObject();
            // The genesis entry has no type.
            if (entry.has("type") && !entry.get("type").isJsonNull() && !entry.get("type").getAsString().isEmpty()) {
                redactions.add(entry.get("type").getAsString() + " " + entry.get("policyName").getAsString());
            }
        }
        return redactions;
    }

    @Test
    @DisplayName("Each page set is redacted with its policy, unassigned pages with p, and the ledger names the policy of each redaction")
    void pageSetsUseTheirPolicies() throws Exception {
        final HttpResponse<byte[]> response = redact("?p=ssn-only&c=" + context + "&async=false" + pagePolicy("2-:email-only"),
                "application/pdf", pdf(3));

        assertEquals(200, response.statusCode(), new String(response.body()));
        assertEquals("ssn-only", response.headers().firstValue("X-Philter-Policy-Name").orElseThrow());
        assertTrue(response.headers().firstValue("X-Philter-Page-Policies").orElseThrow().matches("2-=email-only@\\d+"),
                response.headers().firstValue("X-Philter-Page-Policies").orElseThrow());

        final List<String> redactions = ledgerRedactions(response.headers().firstValue("X-Document-Id").orElseThrow());
        assertEquals(List.of("ssn ssn-only", "email-address email-only", "email-address email-only"), redactions);
    }

    @Test
    @DisplayName("A page set given as a list with commas is read whole, not split")
    void aPageListIsNotSplit() throws Exception {
        final HttpResponse<byte[]> response = redact("?p=ssn-only&c=" + context + "&async=false" + pagePolicy("1,3:email-only"),
                "application/pdf", pdf(3));

        assertEquals(200, response.statusCode(), new String(response.body()));
        assertEquals(List.of("email-address email-only", "ssn ssn-only", "email-address email-only"),
                ledgerRedactions(response.headers().firstValue("X-Document-Id").orElseThrow()));
    }

    @Test
    @DisplayName("A queued PDF is redacted with the page set policies pinned when it was accepted")
    void aQueuedPdfUsesThePinnedPagePolicies() throws Exception {
        final HttpResponse<byte[]> accepted = redact("?p=ssn-only&c=" + context + pagePolicy("2:email-only"),
                "application/pdf", pdf(2));
        assertEquals(202, accepted.statusCode(), new String(accepted.body()));
        assertTrue(accepted.headers().firstValue("X-Philter-Page-Policies").orElseThrow().startsWith("2=email-only@"));
        final String documentId = gson.fromJson(new String(accepted.body()), JsonObject.class).get("documentId").getAsString();

        // A later edit does not change the version that redacts the queued document.
        final HttpResponse<String> edited = send(authenticated("/api/policies/email-only")
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(SSN_POLICY)).build());
        assertEquals(200, edited.statusCode(), edited.body());

        String status = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = send(authenticated("/api/documents/" + documentId + "/status").GET().build());
            status = gson.fromJson(polled.body(), JsonObject.class).get("status").getAsString();
        }
        assertEquals("COMPLETE", status);
        assertEquals(List.of("ssn ssn-only", "email-address email-only"), ledgerRedactions(documentId));
    }

    @Test
    @DisplayName("Overlapping or malformed page sets, and pages beyond the end, are refused with 400")
    void invalidPageSetsAreRefused() throws Exception {
        final HttpResponse<byte[]> overlap = redact("?p=ssn-only&async=false" + pagePolicy("1-2:email-only")
                + pagePolicy("2:ssn-only"), "application/pdf", pdf(2));
        assertEquals(400, overlap.statusCode());
        assertTrue(new String(overlap.body()).contains("Page 2"), new String(overlap.body()));

        assertEquals(400, redact("?p=ssn-only&async=false" + pagePolicy("two:email-only"), "application/pdf", pdf(2)).statusCode());
        assertEquals(400, redact("?p=ssn-only&async=false" + pagePolicy("email-only"), "application/pdf", pdf(2)).statusCode());

        final HttpResponse<byte[]> beyond = redact("?p=ssn-only&async=false" + pagePolicy("2-4:email-only"),
                "application/pdf", pdf(2));
        assertEquals(400, beyond.statusCode());
        assertTrue(new String(beyond.body()).contains("3-4"), new String(beyond.body()));
        assertEquals(400, redact("?p=ssn-only" + pagePolicy("3:email-only"), "application/pdf", pdf(2)).statusCode(),
                "queued too, rather than failing the job");
    }

    @Test
    @DisplayName("A page set naming a policy that does not exist is refused with 404, queued or not")
    void anUnknownPolicyIsNotFound() throws Exception {
        assertEquals(404, redact("?p=ssn-only&async=false" + pagePolicy("1:nosuch"), "application/pdf", pdf(1)).statusCode());
        assertEquals(404, redact("?p=ssn-only" + pagePolicy("1:nosuch"), "application/pdf", pdf(1)).statusCode());
    }

    @Test
    @DisplayName("page_policy on a document that is not a PDF is refused with 400")
    void pagePoliciesAreOnlyForPdfs() throws Exception {
        assertEquals(400, redact("?p=ssn-only&async=false" + pagePolicy("1:email-only"),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                OfficeDocuments.docx(PAGE_TEXT)).statusCode());
        assertEquals(400, send(authenticated("/api/filter?p=ssn-only" + pagePolicy("1:email-only"))
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(PAGE_TEXT)).build()).statusCode());
    }

}
