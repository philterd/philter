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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A PDF redacted in a ledger-enabled context records one ledger entry per redaction, as plain text does. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class PdfLedgerApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    // Two lines on the first page and one on the second, each with one SSN.
    private static final String LINE_1 = "First line SSN 123-45-6789 here.";
    private static final String LINE_2 = "Second line SSN 234-56-7891.";
    private static final String LINE_3 = "Page two SSN 345-67-8912.";

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
        final String username = "pdf-ledger-" + UUID.randomUUID() + "@example.com";
        assertTrue(userService.createUser("req", username, "user", policyDataService, contextDataService, "test").isSuccessful());
        apiKey = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
        final HttpResponse<String> saved = send(authenticated("/api/policies?name=ssn-only")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(SSN_POLICY)).build());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, saved.body());
        context = "pdf-ledger-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpResponse<String> created = send(authenticated("/api/contexts").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + context + "\",\"ledger\":true}")).build());
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpRequest.Builder authenticated(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void showText(final PDDocument document, final String... lines) throws Exception {
        final PDPage page = new PDPage();
        document.addPage(page);
        try (final PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(72, 720);
            for (final String line : lines) {
                content.showText(line);
                content.newLineAtOffset(0, -20);
            }
            content.endText();
        }
    }

    private static byte[] pdf() throws Exception {
        try (final PDDocument document = new PDDocument(); final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            showText(document, LINE_1, LINE_2);
            showText(document, LINE_3);
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("Each redaction in a PDF is a ledger entry, at its offset in the text extracted from the whole document")
    void eachRedactionIsAnEntry() throws Exception {

        final HttpResponse<byte[]> response = httpClient.send(authenticated("/api/filter?p=ssn-only&c=" + context + "&async=false")
                        .header("Content-Type", "application/pdf").header("Accept", "application/pdf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(pdf())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), new String(response.body()));

        final HttpResponse<String> ledger = send(authenticated("/api/ledger/"
                + response.headers().firstValue("X-Document-Id").orElseThrow()).GET().build());
        assertEquals(200, ledger.statusCode(), ledger.body());
        final JsonObject chain = gson.fromJson(ledger.body(), JsonObject.class);
        assertTrue(chain.get("hashChainValid").getAsBoolean(), ledger.body());

        final List<String> redactions = new ArrayList<>();
        for (final JsonElement element : chain.getAsJsonArray("entries")) {
            final JsonObject entry = element.getAsJsonObject();
            // The genesis entry has no type.
            if (!entry.get("type").getAsString().isEmpty()) {
                redactions.add(entry.get("type").getAsString() + "@" + entry.get("startPosition").getAsInt());
            }
        }

        // The extracted lines follow one another with no separator, across pages, so an entry's position is
        // the length of the lines before it plus its position in its own line.
        final int line2 = LINE_1.length();
        final int line3 = line2 + LINE_2.length();
        assertEquals(List.of(
                "ssn@" + LINE_1.indexOf("123-45-6789"),
                "ssn@" + (line2 + LINE_2.indexOf("234-56-7891")),
                "ssn@" + (line3 + LINE_3.indexOf("345-67-8912"))), redactions);

    }

}
