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
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
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
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A policy's config.review.samplingRate is reported with X-Philter-Review and recorded in the audit log. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ReviewSamplingApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String TEXT = "His SSN is 123-45-6789.";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

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
        savePolicy("never-sampled", 0.0);
        savePolicy("always-sampled", 1.0);
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private String keyFor(final String role) {
        final String username = "review-sampling-" + UUID.randomUUID() + "@example.com";
        assertTrue(userService.createUser("req", username, role, policyDataService, contextDataService, "test").isSuccessful());
        return apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    private void savePolicy(final String name, final double samplingRate) throws Exception {
        final String policy = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
                + "\"config\":{\"review\":{\"samplingRate\":" + samplingRate + "}}}";
        final HttpResponse<String> saved = httpClient.send(authenticated(apiKey, "/api/policies?name=" + name)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(policy)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, saved.body());
    }

    private HttpRequest.Builder authenticated(final String key, final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + key);
    }

    private HttpHeaders filterText(final String policy) throws Exception {
        final HttpResponse<String> response = httpClient.send(authenticated(apiKey, "/api/filter?p=" + policy)
                .header("Content-Type", "text/plain").header("Accept", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(TEXT)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("123-45-6789"), "sampling does not change the redaction: " + response.body());
        return response.headers();
    }

    private HttpHeaders filterPdf(final String policy) throws Exception {
        final HttpResponse<byte[]> response = httpClient.send(authenticated(apiKey, "/api/filter?p=" + policy + "&async=false")
                .header("Content-Type", "application/pdf").header("Accept", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pdf())).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), new String(response.body()));
        return response.headers();
    }

    private static byte[] pdf() throws Exception {
        try (final PDDocument document = new PDDocument(); final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final PDPage page = new PDPage();
            document.addPage(page);
            try (final PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(TEXT);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    /** The redaction_review_sampled events recorded under the request id. */
    private List<JsonObject> sampledEvents(final String requestId) throws Exception {
        return sampledEvents(event -> event.has("requestId") && !event.get("requestId").isJsonNull()
                && requestId.equals(event.get("requestId").getAsString()));
    }

    private List<JsonObject> sampledEvents(final java.util.function.Predicate<JsonObject> matching) throws Exception {
        final HttpResponse<String> audit = httpClient.send(authenticated(adminKey,
                "/api/audit?event=redaction_review_sampled&limit=1000").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, audit.statusCode(), audit.body());
        final List<JsonObject> found = new ArrayList<>();
        for (final JsonElement event : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            if (matching.test(event.getAsJsonObject())) {
                found.add(event.getAsJsonObject());
            }
        }
        return found;
    }

    private void assertSampledAndAudited(final HttpHeaders headers) throws Exception {
        assertEquals("sampled", headers.firstValue("X-Philter-Review").orElse(null));
        final List<JsonObject> events = sampledEvents(headers.firstValue("X-Request-Id").orElseThrow());
        assertEquals(1, events.size(), "one event under the request's id");
        final JsonObject event = events.get(0);
        assertEquals("documentId: " + headers.firstValue("X-Document-Id").orElseThrow() + ", policy: always-sampled, "
                + "policyVersion: " + headers.firstValue("X-Philter-Policy-Version").orElseThrow(), event.get("details").getAsString());
    }

    private void assertNotSampled(final HttpHeaders headers) throws Exception {
        assertTrue(headers.firstValue("X-Philter-Review").isEmpty(), "no header when not selected");
        assertTrue(sampledEvents(headers.firstValue("X-Request-Id").orElseThrow()).isEmpty(), "no event when not selected");
    }

    @Test
    @DisplayName("Text: a rate of 1.0 always flags and audits the request, 0.0 never does")
    void text() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertSampledAndAudited(filterText("always-sampled"));
            assertNotSampled(filterText("never-sampled"));
        }
    }

    @Test
    @DisplayName("PDF: a rate of 1.0 always flags and audits the request, 0.0 never does")
    void pdfDocument() throws Exception {
        assertSampledAndAudited(filterPdf("always-sampled"));
        assertNotSampled(filterPdf("never-sampled"));
    }

    /** Submits a PDF for asynchronous redaction, waits for it to complete, and returns the 202's headers. */
    private HttpHeaders queuePdf(final String policy) throws Exception {
        final HttpResponse<String> accepted = httpClient.send(authenticated(apiKey, "/api/filter?p=" + policy)
                .header("Content-Type", "application/pdf").header("Accept", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pdf())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();

        String status = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = httpClient.send(authenticated(apiKey, "/api/documents/" + documentId + "/status")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            status = gson.fromJson(polled.body(), JsonObject.class).get("status").getAsString();
        }
        assertEquals("COMPLETE", status);

        // The 202 carries no X-Document-Id header; the id is in its body.
        final java.util.Map<String, List<String>> headers = new java.util.HashMap<>(accepted.headers().map());
        headers.put("X-Document-Id", List.of(documentId));
        return HttpHeaders.of(headers, (name, value) -> true);
    }

    @Test
    @DisplayName("Queued PDF: the selection is drawn when it is accepted, reported on the 202, and audited once")
    void queuedPdf() throws Exception {
        final HttpHeaders sampled = queuePdf("always-sampled");
        assertSampledAndAudited(sampled);
        // Once in all, whatever its request id: the worker's own draw is not recorded.
        final String documentId = sampled.firstValue("X-Document-Id").orElseThrow();
        assertEquals(1, sampledEvents(event -> event.get("details").getAsString().contains(documentId)).size());
        assertNotSampled(queuePdf("never-sampled"));
    }

    @Test
    @DisplayName("Explain carries the selection in its body and header")
    void explain() throws Exception {
        for (final String policy : new String[]{"always-sampled", "never-sampled"}) {
            final HttpResponse<String> response = httpClient.send(authenticated(apiKey, "/api/explain?p=" + policy)
                    .header("Content-Type", "text/plain").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(TEXT)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            final boolean sampled = "always-sampled".equals(policy);
            assertEquals(sampled, gson.fromJson(response.body(), JsonObject.class).get("reviewSampled").getAsBoolean());
            assertEquals(sampled, response.headers().firstValue("X-Philter-Review").isPresent());
        }
    }

    @Test
    @DisplayName("A sampling rate outside 0.0 to 1.0 is refused when the policy is saved")
    void outOfRangeRateRefused() throws Exception {
        final String policy = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
                + "\"config\":{\"review\":{\"samplingRate\":1.5}}}";
        final HttpResponse<String> saved = httpClient.send(authenticated(apiKey, "/api/policies?name=bad-rate")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(policy)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, saved.statusCode(), saved.body());
    }

}
