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
import ai.philterd.philter.testutil.RtfDocuments;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
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
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RTF documents through /api/filter, synchronously and queued. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class RtfDocumentsApiIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String RTF = "application/rtf";
    private static final String TEXT_RTF = "text/rtf";

    private static final String SSN = "123-45-6789";
    private static final String TEXT = "The patient's SSN is " + SSN + ".";
    private static final String AUTHOR = "Avery Example";

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    // Keeps comments and redacts them, where the default removes them.
    private static final String KEEP_COMMENTS_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
                    + "\"config\":{\"word\":{\"comments\":\"redact\"}}}";

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
        final String username = "rtf-" + UUID.randomUUID() + "@example.com";
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

    private HttpResponse<byte[]> send(final HttpRequest request) throws Exception {
        return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String body(final HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.ISO_8859_1);
    }

    private void savePolicy(final String name, final String json) throws Exception {
        final HttpResponse<byte[]> saved = send(authenticated("/api/policies?name=" + name)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build());
        assertTrue(saved.statusCode() == 200 || saved.statusCode() == 201, body(saved));
    }

    private HttpResponse<byte[]> redact(final String query, final String contentType, final String accept,
                                        final byte[] body) throws Exception {
        return send(authenticated("/api/filter" + query).header("Content-Type", contentType)
                .header("Accept", accept).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    private HttpResponse<byte[]> redact(final String query, final String contentType, final byte[] body) throws Exception {
        return redact(query, contentType, RTF, body);
    }

    private String accepted(final HttpResponse<byte[]> response) {
        assertEquals(202, response.statusCode(), body(response));
        return gson.fromJson(body(response), JsonObject.class).get("documentId").getAsString();
    }

    private JsonObject awaitStatus(final String documentId) throws Exception {
        JsonObject status = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            Thread.sleep(200);
            final HttpResponse<byte[]> polled = send(authenticated("/api/documents/" + documentId + "/status").GET().build());
            status = gson.fromJson(body(polled), JsonObject.class);
            final String value = status.get("status").getAsString();
            if ("COMPLETE".equals(value) || "FAILED".equals(value)) {
                return status;
            }
        }
        return status;
    }

    @Test
    @DisplayName("An RTF document, sent as application/rtf or text/rtf, is redacted synchronously and returned as application/rtf")
    void anRtfDocumentIsRedacted() throws Exception {
        for (final String contentType : new String[]{RTF, TEXT_RTF}) {
            final HttpResponse<byte[]> response = redact("?p=ssn-only&async=false", contentType, RtfDocuments.rtf(TEXT));

            assertEquals(200, response.statusCode(), body(response));
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(RTF), contentType);
            assertTrue(response.headers().firstValue("X-Document-Id").isPresent());
            assertTrue(body(response).startsWith("{\\rtf"), body(response));
            final String text = RtfDocuments.text(response.body());
            assertFalse(text.contains(SSN), text);
            assertTrue(text.contains("{{{REDACTED-ssn}}}"), text);
        }
    }

    @Test
    @DisplayName("A queued RTF document completes and downloads as application/rtf")
    void aQueuedDocumentCompletes() throws Exception {
        final String documentId = accepted(redact("?p=ssn-only&filename=note.rtf", RTF, RtfDocuments.rtf(TEXT)));

        assertEquals("COMPLETE", awaitStatus(documentId).get("status").getAsString());

        final HttpResponse<byte[]> result = send(authenticated("/api/documents/" + documentId).GET().build());
        assertEquals(200, result.statusCode());
        assertTrue(result.headers().firstValue("Content-Type").orElseThrow().startsWith(RTF));
        final String text = RtfDocuments.text(result.body());
        assertFalse(text.contains(SSN), text);
        assertTrue(text.contains("{{{REDACTED-ssn}}}"), text);
    }

    @Test
    @DisplayName("Two policies with different config.word settings give different output, and authorship is cleared under both")
    void eachPolicysWordSettingsApply() throws Exception {
        savePolicy("keep-comments", KEEP_COMMENTS_POLICY);
        final byte[] document = RtfDocuments.commented("Body text", "Ask about " + SSN, AUTHOR);

        final HttpResponse<byte[]> defaults = redact("?p=ssn-only&async=false", RTF, document);
        final HttpResponse<byte[]> kept = redact("?p=keep-comments&async=false", RTF, document);
        assertEquals(200, defaults.statusCode(), body(defaults));
        assertEquals(200, kept.statusCode(), body(kept));

        // The default removes the comment; comments: redact keeps it with its value redacted.
        assertFalse(body(defaults).contains("annotation"), body(defaults));
        assertTrue(body(kept).contains("annotation"), body(kept));
        assertTrue(body(kept).contains("Ask about"), body(kept));
        for (final HttpResponse<byte[]> response : java.util.List.of(defaults, kept)) {
            assertFalse(body(response).contains(SSN), body(response));
            assertFalse(body(response).contains(AUTHOR), body(response));
        }
    }

    @Test
    @DisplayName("A queued RTF document is redacted with the config.word of the policy version pinned when it was accepted")
    void aQueuedDocumentUsesThePinnedWordSettings() throws Exception {
        savePolicy("pinned-word", SSN_POLICY);
        final byte[] document = RtfDocuments.commented("Body text", "Ask about it", AUTHOR);
        final String documentId = accepted(redact("?p=pinned-word", RTF, document));

        // The worker polls every 5 seconds, so this lands before the job is claimed.
        final HttpResponse<byte[]> edited = send(authenticated("/api/policies/pinned-word")
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(KEEP_COMMENTS_POLICY)).build());
        assertEquals(200, edited.statusCode(), body(edited));

        assertEquals("COMPLETE", awaitStatus(documentId).get("status").getAsString());
        final HttpResponse<byte[]> result = send(authenticated("/api/documents/" + documentId).GET().build());
        assertFalse(body(result).contains("annotation"), "the pinned version removes comments: " + body(result));

        final HttpResponse<byte[]> now = redact("?p=pinned-word&async=false", RTF, document);
        assertTrue(body(now).contains("annotation"), "the edited version keeps them: " + body(now));
    }

    @Test
    @DisplayName("A body that is not RTF declared as RTF, and RTF declared as anything else, are refused with 415")
    void mislabelledBodiesAreRefused() throws Exception {
        final byte[] notRtf = "Just some notes.".getBytes(StandardCharsets.UTF_8);
        for (final String query : new String[]{"?p=ssn-only&async=false", "?p=ssn-only"}) {
            assertEquals(415, redact(query, RTF, notRtf).statusCode(), query);
            assertEquals(415, redact(query, TEXT_RTF, notRtf).statusCode(), query);
        }

        // Before this, RTF sent as text/plain ran through the text pipeline and missed escaped values.
        final byte[] rtf = RtfDocuments.rtf(TEXT);
        final HttpResponse<byte[]> asText = redact("?p=ssn-only", "text/plain", "text/plain", rtf);
        assertEquals(415, asText.statusCode(), body(asText));
        assertTrue(body(asText).contains("RTF"), body(asText));
        assertEquals(415, send(authenticated("/api/explain?p=ssn-only").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(rtf)).build()).statusCode());
        assertEquals(415, redact("?p=ssn-only&async=false", "application/pdf", "application/pdf", rtf).statusCode());
    }

    @Test
    @DisplayName("A document nested deeper than RTF_MAX_NESTING_DEPTH is refused with 413, queued or not")
    void theNestingLimitIsEnforced() throws Exception {
        for (final String query : new String[]{"?p=ssn-only&async=false", "?p=ssn-only"}) {
            final HttpResponse<byte[]> tooDeep = redact(query, RTF, RtfDocuments.nested(100, TEXT));
            assertEquals(413, tooDeep.statusCode(), body(tooDeep));
            assertTrue(body(tooDeep).contains("RTF_MAX_NESTING_DEPTH"), body(tooDeep));
        }
        assertEquals(200, redact("?p=ssn-only&async=false", RTF, RtfDocuments.nested(99, TEXT)).statusCode());
    }

    @Test
    @DisplayName("A malformed RTF document is refused with 400 when it is submitted, queued or not")
    void aMalformedDocumentIsRefused() throws Exception {
        final byte[] unclosed = RtfDocuments.bytes("{\\rtf1 {\\b " + TEXT);
        for (final String query : new String[]{"?p=ssn-only&async=false", "?p=ssn-only"}) {
            final HttpResponse<byte[]> refused = redact(query, RTF, unclosed);
            assertEquals(400, refused.statusCode(), query + ": " + body(refused));
            assertTrue(body(refused).contains("cannot be read"), body(refused));
            assertFalse(body(refused).contains(SSN), body(refused));
        }
    }

    @Test
    @DisplayName("sign=true is refused with 400, and an Accept other than application/rtf or */* with 406")
    void signingAndOtherFormatsAreRefused() throws Exception {
        assertEquals(400, redact("?p=ssn-only&async=false&sign=true", RTF, RtfDocuments.rtf(TEXT)).statusCode());
        assertEquals(406, redact("?p=ssn-only&async=false", RTF, "text/plain", RtfDocuments.rtf(TEXT)).statusCode());
    }

    @Test
    @DisplayName("The policy's leftover check reports on an RTF response")
    void theLeftoverCheckIsReported() throws Exception {
        savePolicy("rtf-leftovers", "{\"identifiers\":{\"ssn\":{}},\"config\":{\"review\":{\"leftoverCheck\":"
                + "{\"mode\":\"always\",\"using\":\"broad\"}}}}");
        final HttpResponse<byte[]> response = redact("?p=rtf-leftovers&async=false", RTF,
                RtfDocuments.rtf("Call 212-555-0187 about " + SSN + "."));

        assertEquals(200, response.statusCode(), body(response));
        assertTrue(Integer.parseInt(response.headers().firstValue("X-Philter-Leftovers").orElseThrow()) >= 1);
        assertTrue(response.headers().firstValue("X-Philter-Review").orElseThrow().contains("leftovers"));
    }

    @Test
    @DisplayName("Redactions in an RTF document are recorded in the ledger")
    void redactionsAreLedgered() throws Exception {
        final String context = "rtf-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpResponse<byte[]> created = send(authenticated("/api/contexts").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + context + "\",\"ledger\":true}")).build());
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, body(created));

        final HttpResponse<byte[]> response = redact("?p=ssn-only&c=" + context + "&async=false&filename=a.rtf", RTF,
                RtfDocuments.rtf(TEXT));
        assertEquals(200, response.statusCode(), body(response));

        final String documentId = response.headers().firstValue("X-Document-Id").orElseThrow();
        final HttpResponse<byte[]> ledger = send(authenticated("/api/ledger/" + documentId).GET().build());
        assertEquals(200, ledger.statusCode(), body(ledger));
        final JsonObject chain = gson.fromJson(body(ledger), JsonObject.class);
        assertTrue(chain.get("hashChainValid").getAsBoolean(), body(ledger));
        assertTrue(body(ledger).contains("\"type\":\"ssn\""), body(ledger));
    }

}
