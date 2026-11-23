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
    @DisplayName("Who wrote a Word document is cleared, though the policy detects no names")
    void authorsAreCleared() throws Exception {
        final String author = "Avery Example";
        final HttpResponse<byte[]> response = redact("?p=ssn-only&async=false", DOCX,
                OfficeDocuments.docxByAuthor(TEXT, author));

        assertEquals(200, response.statusCode(), new String(response.body()));
        final String document = docxText(response.body());
        final String core = OfficeDocuments.entry(response.body(), "docProps/core.xml");
        assertFalse(document.contains(author), document);
        assertTrue(document.contains("w:author=\"Redacted\""), document);
        assertFalse(core.contains(author), core);
        assertFalse(document.contains(SSN), document);
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
