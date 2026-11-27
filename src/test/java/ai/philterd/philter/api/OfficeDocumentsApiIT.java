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
import com.google.gson.JsonObject;
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
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Word and Excel documents through /api/filter, synchronously and queued. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class OfficeDocumentsApiIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private static final String SSN = "123-45-6789";
    private static final String TEXT = "The patient's SSN is " + SSN + ".";

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    private static final String AUTHOR = "Avery Example";
    private static final String REDACTED = "{{{REDACTED-ssn}}}";

    // Keeps and redacts, or keeps unscanned, everything the defaults remove, accept, or clear.
    private static final String KEEP_WORD = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
            + "\"config\":{\"word\":{\"comments\":\"redact\",\"trackedChanges\":\"redact\",\"hiddenText\":\"redact\","
            + "\"headersFooters\":\"keep\",\"removeDocumentProperties\":false}}}";
    private static final String KEEP_EXCEL = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
            + "\"config\":{\"excel\":{\"printHeadersFooters\":\"keep\",\"removeDocumentProperties\":false}}}";
    private static final String KEEP_PROPERTIES = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
            + "\"config\":{\"word\":{\"removeDocumentProperties\":false},\"excel\":{\"removeDocumentProperties\":false}}}";

    // Leaves charts, formula and pivot caches, and uninspectable objects as they are.
    private static final String KEEP_PARTS = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
            + "\"config\":{\"word\":{\"charts\":\"keep\",\"uninspectableObjects\":\"keep\"},"
            + "\"excel\":{\"charts\":\"keep\",\"formulaCaches\":\"keep\",\"pivotCaches\":\"keep\",\"uninspectableObjects\":\"keep\"}}}";

    private static final String CONTEXT_SCOPED_POLICY = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":"
            + "[{\"strategy\":\"RANDOM_REPLACE\",\"replacementScope\":\"CONTEXT\"}]}}}";

    @Autowired private Environment environment;
    @Autowired private UserService userService;
    @Autowired private ApiKeyDataService apiKeyDataService;
    @Autowired private PolicyDataService policyDataService;
    @Autowired private ContextDataService contextDataService;

    private final Gson gson = new Gson();
    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;

    @BeforeEach
    void setUp() throws Exception {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        AdminAccessConfig.setOverrideForTesting(true);
        final String username = "office-" + UUID.randomUUID() + "@example.com";
        assertTrue(userService.createUser("req", username, "admin", policyDataService, contextDataService, "test").isSuccessful());
        apiKey = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
        savePolicy("ssn-only", SSN_POLICY);
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

    private HttpResponse<byte[]> redact(final String query, final String contentType, final byte[] body) throws Exception {
        return httpClient.send(authenticated("/api/filter" + query).header("Content-Type", contentType)
                        .header("Accept", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String docxText(final byte[] docx) {
        return OfficeDocuments.entry(docx, "word/document.xml");
    }

    private static String xlsxText(final byte[] xlsx) {
        // A redacted cell holds its new value inline, and the shared string it used is cleared.
        return OfficeDocuments.entry(xlsx, "xl/worksheets/sheet1.xml") + OfficeDocuments.entry(xlsx, "xl/sharedStrings.xml");
    }

    @Test
    @DisplayName("A Word document is redacted synchronously and returned as a Word document")
    void aWordDocumentIsRedacted() throws Exception {
        final HttpResponse<byte[]> response = redact("?p=ssn-only&async=false", DOCX, OfficeDocuments.docx(TEXT));

        assertEquals(200, response.statusCode(), new String(response.body()));
        assertEquals(DOCX, response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.headers().firstValue("X-Document-Id").isPresent());
        final String text = docxText(response.body());
        assertFalse(text.contains(SSN), text);
        assertTrue(text.contains("{{{REDACTED-ssn}}}"), text);
    }

    @Test
    @DisplayName("Who wrote a Word document or Excel workbook is cleared under every combination of settings")
    void authorsAreCleared() throws Exception {
        savePolicy("keep-word", KEEP_WORD);
        savePolicy("keep-excel", KEEP_EXCEL);
        savePolicy("keep-properties", KEEP_PROPERTIES);
        for (final String policy : List.of("ssn-only", "keep-word", "keep-excel", "keep-properties")) {
            final HttpResponse<byte[]> word = redact("?p=" + policy + "&async=false", DOCX,
                    OfficeDocuments.docxWithEverySetting(SSN, AUTHOR));
            final HttpResponse<byte[]> excel = redact("?p=" + policy + "&async=false", XLSX,
                    OfficeDocuments.xlsxWithEverySetting(TEXT, SSN, AUTHOR));
            for (final HttpResponse<byte[]> response : List.of(word, excel)) {
                assertEquals(200, response.statusCode(), policy + ": " + new String(response.body()));
                final String all = OfficeDocuments.allEntries(response.body());
                assertFalse(all.contains(AUTHOR), policy + ": " + all);
                assertTrue(OfficeDocuments.entry(response.body(), "docProps/core.xml").contains("<dc:creator/>")
                        || OfficeDocuments.entry(response.body(), "docProps/core.xml").contains("<dc:creator></dc:creator>"),
                        policy + ": " + OfficeDocuments.entry(response.body(), "docProps/core.xml"));
            }
            if ("keep-word".equals(policy)) {
                // The kept comment and tracked deletion are attributed to Redacted.
                assertTrue(docxText(word.body()).contains("w:author=\"Redacted\""), docxText(word.body()));
                assertTrue(OfficeDocuments.entry(word.body(), "word/comments.xml").contains("w:author=\"Redacted\""));
            }
        }
    }

    @Test
    @DisplayName("Without config.word, comments, tracked deletions, and hidden text are removed, headers are redacted, and properties are cleared")
    void theWordDefaultsApply() throws Exception {
        final HttpResponse<byte[]> response = redact("?p=ssn-only&async=false", DOCX,
                OfficeDocuments.docxWithEverySetting(SSN, AUTHOR));
        assertEquals(200, response.statusCode(), new String(response.body()));

        final String all = OfficeDocuments.allEntries(response.body());
        assertFalse(all.contains(SSN), all);
        assertTrue(OfficeDocuments.entryNames(response.body()).stream().noneMatch(n -> n.contains("comments")),
                OfficeDocuments.entryNames(response.body()).toString());
        assertFalse(all.contains("Ask about"), "the comment is removed: " + all);
        assertFalse(all.contains("Gone"), "the tracked deletion is accepted: " + all);
        assertFalse(all.contains("Secret"), "the hidden text is removed: " + all);
        assertTrue(docxText(response.body()).contains("Body text."), docxText(response.body()));
        assertEquals("Header " + REDACTED, text(OfficeDocuments.entry(response.body(), "word/header1.xml")));
        assertFalse(all.contains("Quarterly notes"), "the title is cleared: " + all);
        assertFalse(all.contains("Example Client"), "custom properties are cleared: " + all);
    }

    @Test
    @DisplayName("Without config.excel, print headers are redacted and properties are cleared")
    void theExcelDefaultsApply() throws Exception {
        final HttpResponse<byte[]> response = redact("?p=ssn-only&async=false", XLSX,
                OfficeDocuments.xlsxWithEverySetting(TEXT, SSN, AUTHOR));
        assertEquals(200, response.statusCode(), new String(response.body()));

        final String all = OfficeDocuments.allEntries(response.body());
        assertFalse(all.contains(SSN), all);
        assertTrue(OfficeDocuments.entry(response.body(), "xl/worksheets/sheet1.xml").contains("Header " + REDACTED),
                OfficeDocuments.entry(response.body(), "xl/worksheets/sheet1.xml"));
        assertFalse(all.contains("Quarterly notes"), "the title is cleared: " + all);
        assertFalse(all.contains("Example Client"), "custom properties are cleared: " + all);
    }

    @Test
    @DisplayName("Without config.word, charts are redacted and uninspectable objects are removed; keep leaves both")
    void theWordChartAndObjectDefaultsApply() throws Exception {
        savePolicy("keep-parts", KEEP_PARTS);
        final byte[] document = OfficeDocuments.docxWithChartAndObject(TEXT, SSN);

        final HttpResponse<byte[]> defaults = redact("?p=ssn-only&async=false", DOCX, document);
        assertEquals(200, defaults.statusCode(), new String(defaults.body()));
        final String chart = OfficeDocuments.entry(defaults.body(), "word/charts/chart1.xml");
        assertFalse(chart.contains(SSN), chart);
        assertTrue(text(chart).contains("Chart for " + REDACTED), chart);
        assertTrue(OfficeDocuments.entryNames(defaults.body()).stream().noneMatch(n -> n.startsWith("word/embeddings/")),
                OfficeDocuments.entryNames(defaults.body()).toString());

        final HttpResponse<byte[]> kept = redact("?p=keep-parts&async=false", DOCX, document);
        assertEquals(200, kept.statusCode(), new String(kept.body()));
        assertTrue(OfficeDocuments.entry(kept.body(), "word/charts/chart1.xml").contains("Chart for " + SSN));
        assertTrue(OfficeDocuments.entryNames(kept.body()).contains("word/embeddings/object1.pptx"),
                OfficeDocuments.entryNames(kept.body()).toString());
        assertFalse(docxText(kept.body()).contains(SSN), "the body is still redacted");
    }

    @Test
    @DisplayName("Without config.excel, charts and formula and pivot caches are redacted and uninspectable objects are removed; keep leaves them")
    void theExcelCacheAndObjectDefaultsApply() throws Exception {
        savePolicy("keep-parts", KEEP_PARTS);
        final byte[] workbook = OfficeDocuments.xlsxWithCachesAndObject(SSN);

        final HttpResponse<byte[]> defaults = redact("?p=ssn-only&async=false", XLSX, workbook);
        assertEquals(200, defaults.statusCode(), new String(defaults.body()));
        final String sheet = OfficeDocuments.entry(defaults.body(), "xl/worksheets/sheet1.xml");
        final String chart = OfficeDocuments.entry(defaults.body(), "xl/charts/chart1.xml");
        final String pivot = OfficeDocuments.entry(defaults.body(), "xl/pivotCache/pivotCacheDefinition1.xml");
        assertFalse(sheet.contains(SSN), "the cell and the formula's cached result: " + sheet);
        assertFalse(chart.contains(SSN), chart);
        assertFalse(pivot.contains(SSN), pivot);
        // A redacted cell clears the cached results and has the workbook recalculate when it is opened.
        assertTrue(OfficeDocuments.entry(defaults.body(), "xl/workbook.xml").contains("fullCalcOnLoad=\"1\""),
                OfficeDocuments.entry(defaults.body(), "xl/workbook.xml"));
        assertTrue(OfficeDocuments.entryNames(defaults.body()).stream().noneMatch(n -> n.startsWith("xl/embeddings/")),
                OfficeDocuments.entryNames(defaults.body()).toString());

        final HttpResponse<byte[]> kept = redact("?p=keep-parts&async=false", XLSX, workbook);
        assertEquals(200, kept.statusCode(), new String(kept.body()));
        final String keptSheet = OfficeDocuments.entry(kept.body(), "xl/worksheets/sheet1.xml");
        assertTrue(keptSheet.contains("<f>A2</f><v>" + SSN + "</v>"), "formulaCaches: keep leaves the cached result: " + keptSheet);
        assertTrue(OfficeDocuments.entry(kept.body(), "xl/charts/chart1.xml").contains("Chart for " + SSN));
        assertTrue(OfficeDocuments.entry(kept.body(), "xl/pivotCache/pivotCacheDefinition1.xml").contains(SSN));
        assertTrue(OfficeDocuments.entryNames(kept.body()).contains("xl/embeddings/object1.pptx"),
                OfficeDocuments.entryNames(kept.body()).toString());
    }

    @Test
    @DisplayName("Two policies with different config.word settings give different Word output through one instance")
    void eachPolicysWordSettingsApply() throws Exception {
        savePolicy("keep-word", KEEP_WORD);
        final byte[] document = OfficeDocuments.docxWithEverySetting(SSN, AUTHOR);

        final HttpResponse<byte[]> defaults = redact("?p=ssn-only&async=false", DOCX, document);
        final HttpResponse<byte[]> kept = redact("?p=keep-word&async=false", DOCX, document);
        assertEquals(200, defaults.statusCode(), new String(defaults.body()));
        assertEquals(200, kept.statusCode(), new String(kept.body()));

        assertFalse(OfficeDocuments.allEntries(defaults.body()).contains("Ask about"));
        assertEquals("Ask about " + REDACTED, text(OfficeDocuments.entry(kept.body(), "word/comments.xml")));
        final String body = docxText(kept.body());
        assertTrue(body.contains("<w:del ") && body.contains("<w:vanish/>"), body);
        assertEquals("Body text. Gone " + REDACTED + " Secret " + REDACTED, text(body));
        // headersFooters: keep leaves the header unscanned.
        assertEquals("Header " + SSN, text(OfficeDocuments.entry(kept.body(), "word/header1.xml")));
        assertTrue(OfficeDocuments.entry(kept.body(), "docProps/core.xml").contains("Quarterly notes"));
        assertTrue(OfficeDocuments.entry(kept.body(), "docProps/custom.xml").contains("Example Client"));
    }

    @Test
    @DisplayName("Two policies with different config.excel settings give different Excel output through one instance")
    void eachPolicysExcelSettingsApply() throws Exception {
        savePolicy("keep-excel", KEEP_EXCEL);
        final byte[] workbook = OfficeDocuments.xlsxWithEverySetting(TEXT, SSN, AUTHOR);

        final HttpResponse<byte[]> defaults = redact("?p=ssn-only&async=false", XLSX, workbook);
        final HttpResponse<byte[]> kept = redact("?p=keep-excel&async=false", XLSX, workbook);
        assertEquals(200, defaults.statusCode(), new String(defaults.body()));
        assertEquals(200, kept.statusCode(), new String(kept.body()));

        final String defaultSheet = OfficeDocuments.entry(defaults.body(), "xl/worksheets/sheet1.xml");
        final String keptSheet = OfficeDocuments.entry(kept.body(), "xl/worksheets/sheet1.xml");
        assertTrue(defaultSheet.contains("Header " + REDACTED), defaultSheet);
        assertTrue(keptSheet.contains("Header " + SSN), "printHeadersFooters: keep leaves it unscanned: " + keptSheet);
        assertFalse(xlsxText(kept.body()).replace("Header " + SSN, "").contains(SSN), "cells are still redacted");
        assertFalse(OfficeDocuments.entry(defaults.body(), "docProps/core.xml").contains("Quarterly notes"));
        assertTrue(OfficeDocuments.entry(kept.body(), "docProps/core.xml").contains("Quarterly notes"));
    }

    @Test
    @DisplayName("A queued Word document is redacted with the config.word of the policy version pinned when it was accepted")
    void aQueuedDocumentUsesThePinnedWordSettings() throws Exception {
        savePolicy("pinned-word", SSN_POLICY);
        final byte[] document = OfficeDocuments.docxWithEverySetting(SSN, AUTHOR);
        final HttpResponse<byte[]> accepted = redact("?p=pinned-word&filename=pinned.docx", DOCX, document);
        assertEquals(202, accepted.statusCode(), new String(accepted.body()));
        final String documentId = gson.fromJson(new String(accepted.body()), JsonObject.class).get("documentId").getAsString();

        // The worker polls every 5 seconds, so this lands before the job is claimed.
        final HttpResponse<String> edited = send(authenticated("/api/policies/pinned-word")
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(KEEP_WORD)).build());
        assertEquals(200, edited.statusCode(), edited.body());

        assertEquals("COMPLETE", awaitStatus(documentId));
        final HttpResponse<byte[]> result = httpClient.send(authenticated("/api/documents/" + documentId).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertFalse(OfficeDocuments.allEntries(result.body()).contains("Ask about"),
                "the pinned version removes comments: " + OfficeDocuments.entryNames(result.body()));

        final HttpResponse<byte[]> now = redact("?p=pinned-word&async=false", DOCX, document);
        assertTrue(OfficeDocuments.allEntries(now.body()).contains("Ask about"), "the edited version keeps them");
    }

    @Test
    @DisplayName("A value in a removed comment is not recorded in the ledger; in a kept, redacted comment it is")
    void removedContentIsNotLedgered() throws Exception {
        savePolicy("keep-word", KEEP_WORD);
        final String context = "office-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpResponse<String> created = send(authenticated("/api/contexts").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + context + "\",\"ledger\":true}")).build());
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());
        // The value is only in the comment, the tracked deletion, the hidden run, and the header, which keep-word leaves unscanned.
        final byte[] document = OfficeDocuments.docxWithEverySetting(SSN, AUTHOR);

        final HttpResponse<byte[]> removed = redact("?p=ssn-only&c=" + context + "&async=false&filename=removed.docx", DOCX, document);
        final HttpResponse<byte[]> kept = redact("?p=keep-word&c=" + context + "&async=false&filename=kept.docx", DOCX, document);
        assertEquals(200, removed.statusCode(), new String(removed.body()));
        assertEquals(200, kept.statusCode(), new String(kept.body()));

        // Under the defaults, only the header's value is detected; the rest is removed before detection.
        assertEquals(1, ssnEntries(removed), "the header only");
        // Under keep-word, the comment, deletion, and hidden run are detected, and the header is not scanned.
        assertEquals(3, ssnEntries(kept), "the comment, the deletion, and the hidden run");
    }

    @Test
    @DisplayName("A policy with an invalid config.word or config.excel value is refused when it is created or updated")
    void invalidSettingsAreRefused() throws Exception {
        final String invalidWord = "{\"identifiers\":{\"ssn\":{}},\"config\":{\"word\":{\"comments\":\"keep\"}}}";
        final String invalidExcel = "{\"identifiers\":{\"ssn\":{}},\"config\":{\"excel\":{\"colour\":\"red\"}}}";
        for (final String invalid : List.of(invalidWord, invalidExcel)) {
            final HttpResponse<String> created = send(authenticated("/api/policies?name=invalid-office")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(invalid)).build());
            assertEquals(400, created.statusCode(), created.body());
            assertEquals(404, send(authenticated("/api/policies/invalid-office").GET().build()).statusCode());

            final HttpResponse<String> updated = send(authenticated("/api/policies/ssn-only")
                    .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(invalid)).build());
            assertEquals(400, updated.statusCode(), updated.body());
        }
        assertEquals(200, redact("?p=ssn-only&async=false", DOCX, OfficeDocuments.docx(TEXT)).statusCode(),
                "the stored policy is unchanged");
    }

    // The text of an XML part, without its markup.
    private static String text(final String xml) {
        return xml.replaceFirst("^<\\?xml[^>]*\\?>", "").replaceAll("<[^>]+>", "");
    }

    private int ssnEntries(final HttpResponse<byte[]> response) throws Exception {
        final String documentId = response.headers().firstValue("X-Document-Id").orElseThrow();
        final HttpResponse<String> ledger = send(authenticated("/api/ledger/" + documentId).GET().build());
        assertEquals(200, ledger.statusCode(), ledger.body());
        assertTrue(gson.fromJson(ledger.body(), JsonObject.class).get("hashChainValid").getAsBoolean(), ledger.body());
        return ledger.body().split("\"type\":\"ssn\"", -1).length - 1;
    }

    private String awaitStatus(final String documentId) throws Exception {
        String status = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = send(authenticated("/api/documents/" + documentId + "/status").GET().build());
            status = gson.fromJson(polled.body(), JsonObject.class).get("status").getAsString();
        }
        return status;
    }

    @Test
    @DisplayName("An Excel workbook is redacted synchronously and returned as an Excel workbook")
    void anExcelWorkbookIsRedacted() throws Exception {
        final HttpResponse<byte[]> response = redact("?p=ssn-only&async=false", XLSX, OfficeDocuments.xlsx(TEXT));

        assertEquals(200, response.statusCode(), new String(response.body()));
        assertEquals(XLSX, response.headers().firstValue("Content-Type").orElseThrow());
        final String text = xlsxText(response.body());
        assertFalse(text.contains(SSN), text);
        assertTrue(text.contains("{{{REDACTED-ssn}}}"), text);
    }

    @Test
    @DisplayName("A queued Word document completes and downloads as a Word document")
    void aQueuedWordDocumentCompletes() throws Exception {
        final HttpResponse<byte[]> accepted = redact("?p=ssn-only&filename=note.docx", DOCX, OfficeDocuments.docx(TEXT));
        assertEquals(202, accepted.statusCode(), new String(accepted.body()));
        final String documentId = gson.fromJson(new String(accepted.body()), JsonObject.class).get("documentId").getAsString();

        String status = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = send(authenticated("/api/documents/" + documentId + "/status").GET().build());
            status = gson.fromJson(polled.body(), JsonObject.class).get("status").getAsString();
        }
        assertEquals("COMPLETE", status);

        final HttpResponse<byte[]> result = httpClient.send(authenticated("/api/documents/" + documentId).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, result.statusCode());
        assertEquals(DOCX, result.headers().firstValue("Content-Type").orElseThrow());
        final String text = docxText(result.body());
        assertFalse(text.contains(SSN), text);
        assertTrue(text.contains("{{{REDACTED-ssn}}}"), text);
    }

    @Test
    @DisplayName("A context gives a value the same replacement in a Word document and an Excel workbook, and records both in the ledger")
    void aContextKeepsReplacementsConsistentAndLedgersThem() throws Exception {
        savePolicy("ssn-context", CONTEXT_SCOPED_POLICY);
        final String context = "office-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpResponse<String> created = send(authenticated("/api/contexts").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + context + "\",\"ledger\":true}")).build());
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());

        final HttpResponse<byte[]> word = redact("?p=ssn-context&c=" + context + "&async=false&filename=a.docx", DOCX,
                OfficeDocuments.docx(TEXT));
        final HttpResponse<byte[]> excel = redact("?p=ssn-context&c=" + context + "&async=false&filename=b.xlsx", XLSX,
                OfficeDocuments.xlsx(TEXT));
        assertEquals(200, word.statusCode(), new String(word.body()));
        assertEquals(200, excel.statusCode(), new String(excel.body()));

        final String inWord = replacement(docxText(word.body()));
        final String inExcel = replacement(xlsxText(excel.body()));
        assertFalse(inWord.contains(SSN), inWord);
        assertEquals(inWord, inExcel, "the same value is replaced the same way in both documents");

        for (final HttpResponse<byte[]> response : java.util.List.of(word, excel)) {
            final String documentId = response.headers().firstValue("X-Document-Id").orElseThrow();
            final HttpResponse<String> ledger = send(authenticated("/api/ledger/" + documentId).GET().build());
            assertEquals(200, ledger.statusCode(), ledger.body());
            final JsonObject chain = gson.fromJson(ledger.body(), JsonObject.class);
            assertTrue(chain.get("hashChainValid").getAsBoolean(), ledger.body());
            assertTrue(ledger.body().contains("\"type\":\"ssn\""), ledger.body());
        }
    }

    // The replacement in "The patient's SSN is <replacement>.", read from the text with the markup removed.
    private static String replacement(final String xml) {
        final String text = xml.replaceAll("<[^>]+>", "");
        final Matcher matcher = Pattern.compile("SSN is (.+?)\\.").matcher(text);
        assertTrue(matcher.find(), text);
        return matcher.group(1);
    }

    @Test
    @DisplayName("An Excel workbook declared as Word, and a ZIP that is not Office, are refused with 415")
    void mislabelledBodiesAreRefused() throws Exception {
        assertEquals(415, redact("?p=ssn-only&async=false", DOCX, OfficeDocuments.xlsx(TEXT)).statusCode());
        assertEquals(415, redact("?p=ssn-only&async=false", XLSX, OfficeDocuments.plainZip()).statusCode());
        assertEquals(415, redact("?p=ssn-only", XLSX, OfficeDocuments.plainZip()).statusCode(), "queued too");
        assertEquals(415, send(authenticated("/api/filter?p=ssn-only").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(OfficeDocuments.docx(TEXT))).build()).statusCode());
    }

    @Test
    @DisplayName("A Word document asking for a signature is refused")
    void signingIsRefused() throws Exception {
        assertEquals(400, redact("?p=ssn-only&async=false&sign=true", DOCX, OfficeDocuments.docx(TEXT)).statusCode());
    }

}
