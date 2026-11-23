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
import ai.philterd.philter.testutil.OfficeDocuments;
import com.google.gson.Gson;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redacting with a managed policy named as p or in page_policy, without copying it first. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ManagedPolicyFilterApiIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    // Every managed policy redacts SSNs and email addresses.
    private static final String TEXT = "SSN 123-45-6789 and email jane.doe@example.com.";

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
    void setUp() {
        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);
        final String username = "managed-" + UUID.randomUUID() + "@example.com";
        assertTrue(userService.createUser("req", username, "user", policyDataService, contextDataService, "test").isSuccessful());
        apiKey = apiKeyDataService.createApiKey("req", userService.findByUsername(username).getId(), "test",
                ApiKeyScope.all()).getMessage();
    }

    @AfterEach
    void tearDown() {
        httpClient.close();
    }

    private HttpRequest.Builder authenticated(final String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer " + apiKey);
    }

    private HttpResponse<String> filterText(final String policy) throws Exception {
        return httpClient.send(authenticated("/api/filter?p=" + policy).header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(TEXT)).build(), HttpResponse.BodyHandlers.ofString());
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

    @ParameterizedTest
    @ValueSource(strings = {"managed_common_pii", "managed_healthcare_phi", "managed_financial_pii"})
    @DisplayName("A managed policy named as p redacts text, and the response headers name its version")
    void aManagedPolicyRedactsText(final String policy) throws Exception {
        final HttpResponse<String> response = filterText(policy);

        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains("123-45-6789"), response.body());
        assertFalse(response.body().contains("jane.doe@example.com"), response.body());
        assertEquals(policy, response.headers().firstValue("X-Philter-Policy-Name").orElseThrow());

        final JsonObject stored = gson.fromJson(httpClient.send(authenticated("/api/policies/" + policy + "/details").GET().build(),
                HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
        assertEquals(stored.get("revision").getAsString(),
                response.headers().firstValue("X-Philter-Policy-Version").orElseThrow(),
                "the version of the managed policy that was applied");
    }

    @Test
    @DisplayName("/api/explain explains a redaction with a managed policy and names it")
    void explainUsesAManagedPolicy() throws Exception {
        final HttpResponse<String> response = httpClient.send(authenticated("/api/explain?p=managed_financial_pii")
                .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString(TEXT)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), response.body());
        final JsonObject explained = gson.fromJson(response.body(), JsonObject.class);
        assertEquals("managed_financial_pii", explained.get("policyName").getAsString());
        assertFalse(explained.get("filteredText").getAsString().contains("123-45-6789"), response.body());
    }

    @Test
    @DisplayName("A name with the managed prefix that is not a managed policy is still 404")
    void anUnknownManagedNameIsNotFound() throws Exception {
        assertEquals(404, filterText("managed_nosuch").statusCode());
    }

    @Test
    @DisplayName("A queued Word document is redacted with a managed policy")
    void aQueuedDocumentUsesAManagedPolicy() throws Exception {
        final String contentType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        final HttpResponse<String> accepted = httpClient.send(authenticated("/api/filter?p=managed_common_pii")
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(OfficeDocuments.docx(TEXT))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();

        String status = null;
        for (int attempt = 0; attempt < 150 && !"COMPLETE".equals(status) && !"FAILED".equals(status); attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = httpClient.send(authenticated("/api/documents/" + documentId + "/status")
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            status = gson.fromJson(polled.body(), JsonObject.class).get("status").getAsString();
        }
        assertEquals("COMPLETE", status);

        final HttpResponse<byte[]> redacted = httpClient.send(authenticated("/api/documents/" + documentId).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, redacted.statusCode());
        final String body = OfficeDocuments.entry(redacted.body(), "word/document.xml");
        assertFalse(body.contains("123-45-6789"), body);
        assertFalse(body.contains("jane.doe@example.com"), body);
        assertTrue(body.contains(" and email "), "the text between the redactions is kept: " + body);
    }

    @Test
    @DisplayName("A managed policy can be named in page_policy")
    void aManagedPolicyCanBeAPagePolicy() throws Exception {
        final byte[] pdf = pdf(2);
        final HttpResponse<byte[]> response = httpClient.send(authenticated("/api/filter?p=managed_financial_pii&async=false"
                        + "&page_policy=" + URLEncoder.encode("2:managed_common_pii", StandardCharsets.UTF_8))
                        .header("Content-Type", "application/pdf").header("Accept", "application/pdf")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertEquals(200, response.statusCode(), new String(response.body()));
        assertTrue(response.headers().firstValue("X-Philter-Page-Policies").orElseThrow().startsWith("2=managed_common_pii@"),
                response.headers().firstValue("X-Philter-Page-Policies").orElseThrow());
    }

    @Test
    @DisplayName("Re-identifying with a managed policy says it has no crypto key, rather than that it does not exist")
    void reidentifyResolvesManagedPolicies() throws Exception {
        final String request = "{\"values\":[\"abc\"],\"strategy\":\"CRYPTO_REPLACE\","
                + "\"policyName\":\"managed_common_pii\",\"reason\":\"test\"}";
        final HttpResponse<String> response = httpClient.send(authenticated("/api/reidentify")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(400, response.statusCode(), response.body());
        assertTrue(response.body().contains("has no crypto key configured"), response.body());
    }

}
