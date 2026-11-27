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

import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.testutil.EmailMessages;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import ai.philterd.philter.testutil.LogCapture;
import ai.philterd.philter.testutil.OfficeDocuments;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A policy's config.review.leftoverCheck, requested with leftover_check and reported in headers, explain, audit, and metrics. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class LeftoverCheckApiIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    // The policies redact email addresses only, so the SSN is left in the output for the broad check to find.
    private static final String SSN = "123-45-6789";
    private static final String TEXT = "Write to avery@example.com about SSN " + SSN + ".";

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String EML = "message/rfc822";
    private static final String MSG = "application/vnd.ms-outlook";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;
    @Autowired private MeterRegistry meterRegistry;

    private final Gson gson = new Gson();
    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private String adminKey;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        apiKey = keyFor("user");
        adminKey = keyFor("admin");
        savePolicy("on-request", "{\"mode\":\"onRequest\"}", "");
        savePolicy("always", "{\"mode\":\"always\"}", "");
        savePolicy("off", "{\"mode\":\"off\"}", "");
        savePolicy("always-sampled", "{\"mode\":\"always\"}", ",\"samplingRate\":1.0");
        savePolicy("no-check", null, "");
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private String keyFor(final String role) {
        final String username = "leftovers-" + UUID.randomUUID() + "@example.com";
        assertTrue(userService.createUser("req", username, role, policyDataService, contextDataService, "test").isSuccessful());
        return apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private void savePolicy(final String name, final String leftoverCheck, final String review) throws Exception {
        final String config = leftoverCheck == null ? ""
                : ",\"config\":{\"review\":{\"leftoverCheck\":" + leftoverCheck + review + "}}";
        final String policy = "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}"
                + config + "}";
        final HttpResponse<String> saved = send(authenticated(apiKey, "/api/policies?name=" + name)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(policy)).build());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, saved.body());
    }

    private HttpRequest.Builder authenticated(final String key, final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + key);
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(final String path, final String contentType, final String accept, final byte[] body)
            throws Exception {
        return send(authenticated(apiKey, path).header("Content-Type", contentType).header("Accept", accept)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    private HttpResponse<String> text(final String query) throws Exception {
        return post("/api/filter" + query, "text/plain", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8));
    }

    private static String leftovers(final HttpHeaders headers) {
        return headers.firstValue("X-Philter-Leftovers").orElse(null);
    }

    private static String review(final HttpHeaders headers) {
        return headers.firstValue("X-Philter-Review").orElse(null);
    }

    private static void assertFound(final HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        final String count = leftovers(response.headers());
        assertNotNull(count, "X-Philter-Leftovers");
        assertTrue(Integer.parseInt(count) > 0, count);
        assertEquals("leftovers", review(response.headers()));
    }

    private static void assertNotChecked(final HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(null, leftovers(response.headers()));
        assertEquals(null, review(response.headers()));
    }

    @Test
    @DisplayName("onRequest checks only with leftover_check=true")
    void onRequest() throws Exception {
        assertFound(text("?p=on-request&leftover_check=true"));
        assertNotChecked(text("?p=on-request"));
        assertNotChecked(text("?p=on-request&leftover_check=false"));
    }

    @Test
    @DisplayName("always checks every request, and leftover_check=false does not turn it off")
    void always() throws Exception {
        assertFound(text("?p=always"));
        assertFound(text("?p=always&leftover_check=false"));
        assertFound(text("?p=always&leftover_check=true"));
    }

    @Test
    @DisplayName("leftover_check=true under mode off, or with no leftover check set, is refused with 400, queued or not")
    void offIsRefused() throws Exception {
        for (final String policy : new String[]{"off", "no-check"}) {
            final HttpResponse<String> refused = text("?p=" + policy + "&leftover_check=true");
            assertEquals(400, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("leftover"), refused.body());
            assertEquals(400, post("/api/filter?p=" + policy + "&leftover_check=true", DOCX, DOCX,
                    OfficeDocuments.docx(TEXT)).statusCode(), "queued");
            assertNotChecked(text("?p=" + policy));
        }
    }

    @Test
    @DisplayName("X-Philter-Review lists sampled and leftovers when both apply")
    void sampledAndLeftovers() throws Exception {
        final HttpResponse<String> response = text("?p=always-sampled");
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("sampled, leftovers", review(response.headers()));
    }

    @Test
    @DisplayName("Explain returns leftoverCheck with each finding's type, position, and confidence, and no matched text")
    void explain() throws Exception {
        final HttpResponse<String> response = post("/api/explain?p=on-request&leftover_check=true", "text/plain",
                "application/json", TEXT.getBytes(StandardCharsets.UTF_8));
        assertFound(response);
        final JsonObject check = gson.fromJson(response.body(), JsonObject.class).getAsJsonObject("leftoverCheck");
        assertNotNull(check, response.body());
        assertTrue(check.get("performed").getAsBoolean());
        assertFalse(check.toString().contains(SSN), check.toString());
        final JsonObject finding = check.getAsJsonArray("findings").get(0).getAsJsonObject();
        for (final String field : new String[]{"filterType", "characterStart", "characterEnd", "confidence"}) {
            assertTrue(finding.has(field), field + " in " + finding);
        }
        final int start = finding.get("characterStart").getAsInt();
        final String filtered = gson.fromJson(response.body(), JsonObject.class).get("filteredText").getAsString();
        assertEquals(SSN, filtered.substring(start, finding.get("characterEnd").getAsInt()));
    }

    @Test
    @DisplayName("Word, Excel, .eml, and .msg responses carry the leftover check and, for email, the email report")
    void documents() throws Exception {
        assertFound(post("/api/filter?p=always&async=false", DOCX, DOCX, OfficeDocuments.docx(TEXT)));
        assertFound(post("/api/filter?p=always&async=false", XLSX, XLSX, OfficeDocuments.xlsx(TEXT)));
        assertEquals(null, post("/api/filter?p=always&async=false", DOCX, DOCX, OfficeDocuments.docx(TEXT)).headers()
                .firstValue("X-Philter-Email-Report").orElse(null), "only for email");

        final HttpResponse<String> eml = post("/api/filter?p=always&async=false", EML, EML,
                EmailMessages.emlWithAttachment(TEXT, "notes.txt", "text/plain", "Notes".getBytes(StandardCharsets.UTF_8)));
        assertFound(eml);
        assertEquals("uninspected-attachments=0, uninspected-inline-images=0, removed-attachments=1, "
                + "removed-inline-images=0, dropped-signatures=0, unchecked-attachments=0",
                eml.headers().firstValue("X-Philter-Email-Report").orElse(null));

        final HttpResponse<String> msg = post("/api/filter?p=always&async=false", MSG, EML, EmailMessages.msg("Hello", TEXT));
        assertFound(msg);
        assertTrue(msg.headers().firstValue("X-Philter-Email-Report").isPresent());

        // Without the check, the email report is still returned, without the unchecked count.
        assertEquals("uninspected-attachments=0, uninspected-inline-images=0, removed-attachments=0, "
                + "removed-inline-images=0, dropped-signatures=0",
                post("/api/filter?p=no-check&async=false", EML, EML, EmailMessages.eml("Hello", TEXT)).headers()
                        .firstValue("X-Philter-Email-Report").orElse(null));
    }

    @Test
    @DisplayName("A PDF whose redacted page has no text reports not-checked, and page_policy uses the p policy's setting")
    void pdf() throws Exception {
        final HttpResponse<String> checked = post("/api/filter?p=always&async=false", "application/pdf", "application/pdf", pdf(2));
        assertEquals(200, checked.statusCode(), checked.body());
        assertEquals("not-checked", leftovers(checked.headers()));
        assertEquals(null, review(checked.headers()), "not-checked is not a finding");

        assertEquals("not-checked", leftovers(post("/api/filter?p=always&async=false&page_policy="
                + URLEncoder.encode("2:no-check", StandardCharsets.UTF_8), "application/pdf", "application/pdf", pdf(2)).headers()));
        assertEquals(null, leftovers(post("/api/filter?p=no-check&async=false&page_policy="
                + URLEncoder.encode("2:always", StandardCharsets.UTF_8), "application/pdf", "application/pdf", pdf(2)).headers()));
    }

    @Test
    @DisplayName("A queued document's result is returned on its status and download responses")
    void queued() throws Exception {
        final HttpResponse<String> word = post("/api/filter?p=on-request&leftover_check=true", DOCX, DOCX, OfficeDocuments.docx(TEXT));
        assertEquals(202, word.statusCode(), word.body());
        assertEquals(null, leftovers(word.headers()), "the 202 comes before the check runs");
        final String wordId = await(word);
        assertFound(send(authenticated(apiKey, "/api/documents/" + wordId + "/status").GET().build()));
        assertFound(send(authenticated(apiKey, "/api/documents/" + wordId).GET().build()));
        // Checked by the worker, so its audit record is keyed by the document ID.
        final HttpResponse<String> events = send(authenticated(adminKey,
                "/api/audit?event=redaction_leftovers_found&limit=1000").GET().build());
        int recorded = 0;
        for (final JsonElement event : gson.fromJson(events.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = event.getAsJsonObject();
            if (e.has("requestId") && !e.get("requestId").isJsonNull() && wordId.equals(e.get("requestId").getAsString())) {
                assertTrue(e.get("details").getAsString().contains("documentId: " + wordId), e.toString());
                recorded++;
            }
        }
        assertEquals(1, recorded, events.body());

        final HttpResponse<String> unchecked = post("/api/filter?p=on-request", DOCX, DOCX, OfficeDocuments.docx(TEXT));
        final String uncheckedId = await(unchecked);
        assertNotChecked(send(authenticated(apiKey, "/api/documents/" + uncheckedId + "/status").GET().build()));

        final HttpResponse<String> email = post("/api/filter?p=always", EML, EML,
                EmailMessages.emlWithInlineImageAndAttachment(TEXT, "notes.txt", "text/plain",
                        "Notes".getBytes(StandardCharsets.UTF_8)));
        final String emailId = await(email);
        final HttpResponse<String> emailStatus = send(authenticated(apiKey, "/api/documents/" + emailId + "/status")
                .GET().build());
        final HttpResponse<String> download = send(authenticated(apiKey, "/api/documents/" + emailId).GET().build());
        assertFound(download);
        for (final HttpResponse<String> response : List.of(emailStatus, download)) {
            assertEquals("uninspected-attachments=0, uninspected-inline-images=0, removed-attachments=1, "
                    + "removed-inline-images=1, dropped-signatures=0, unchecked-attachments=0",
                    response.headers().firstValue("X-Philter-Email-Report").orElse(null));
        }
    }

    private String await(final HttpResponse<String> accepted) throws Exception {
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();
        String status = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            status = gson.fromJson(send(authenticated(apiKey, "/api/documents/" + documentId + "/status").GET().build())
                    .body(), JsonObject.class).get("status").getAsString();
        }
        assertEquals("COMPLETE", status);
        return documentId;
    }

    @Test
    @DisplayName("Findings are audited and counted, and a finding's text reaches no header, log line, audit record, or metric")
    void auditMetricsAndNoMatchedText() throws Exception {
        final double foundBefore = counter("philter.leftover.checks", "outcome", "found");
        final double ssnBefore = counter("philter.leftover.findings", "filter_type", "ssn");

        final HttpResponse<String> response;
        try (LogCapture logs = new LogCapture()) {
            response = text("?p=always");
            assertFound(response);
            assertFalse(logs.contains(SSN), "a log line holds the finding's text");
        }
        response.headers().map().forEach((name, values) ->
                values.forEach(value -> assertFalse(value.contains(SSN), name + ": " + value)));

        assertEquals(foundBefore + 1, counter("philter.leftover.checks", "outcome", "found"));
        assertTrue(counter("philter.leftover.findings", "filter_type", "ssn") > ssnBefore);
        for (final Meter meter : meterRegistry.getMeters()) {
            if (meter.getId().getName().startsWith("philter.leftover")) {
                for (final Tag tag : meter.getId().getTags()) {
                    assertTrue(List.of("outcome", "filter_type").contains(tag.getKey()), meter.getId().toString());
                }
            }
            assertFalse(meter.getId().toString().contains(SSN), meter.getId().toString());
        }

        final String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        final HttpResponse<String> audit = send(authenticated(adminKey, "/api/audit?limit=1000").GET().build());
        assertEquals(200, audit.statusCode(), audit.body());
        assertFalse(audit.body().contains(SSN), "an audit record holds the finding's text");
        final HttpResponse<String> leftoverEvents = send(authenticated(adminKey,
                "/api/audit?event=redaction_leftovers_found&limit=1000").GET().build());
        assertEquals(200, leftoverEvents.statusCode(), leftoverEvents.body());
        JsonObject found = null;
        for (final JsonElement event : gson.fromJson(leftoverEvents.body(), JsonObject.class).getAsJsonArray("events")) {
            final JsonObject e = event.getAsJsonObject();
            if (e.has("requestId") && !e.get("requestId").isJsonNull() && requestId.equals(e.get("requestId").getAsString())) {
                assertEquals(null, found, "one record per request");
                found = e;
            }
        }
        assertNotNull(found, "a redaction_leftovers_found record for the request");
        final String details = found.get("details").getAsString();
        final String documentId = response.headers().firstValue("X-Document-Id").orElseThrow();
        assertTrue(details.contains(documentId), details);
        assertTrue(details.contains("policy: always,") && details.contains("policyVersion: "), details);
        assertTrue(details.contains("count: " + leftovers(response.headers())), details);
        assertTrue(details.contains("types: ssn"), details);
    }

    private double counter(final String name, final String tag, final String value) {
        final var counter = meterRegistry.find(name).tag(tag, value).counter();
        return counter == null ? 0 : counter.count();
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
                    content.showText(TEXT);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }

}
