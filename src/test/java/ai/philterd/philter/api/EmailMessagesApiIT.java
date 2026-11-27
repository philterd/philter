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
import ai.philterd.philter.testutil.EmailMessages;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import ai.philterd.philter.testutil.RtfDocuments;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Email messages (.eml and .msg) through /api/filter, synchronously and queued. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class EmailMessagesApiIT {

    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String EML = "message/rfc822";
    private static final String MSG = "application/vnd.ms-outlook";

    private static final String SSN = "123-45-6789";
    private static final String TEXT = "The patient's SSN is " + SSN + ".";

    private static final String SSN_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    // Removes the Date header and keeps inline images, where the defaults do the opposite.
    private static final String STRICT_EMAIL_POLICY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
                    + "\"config\":{\"email\":{\"headers\":{\"removeTechnical\":true,\"removeIdentity\":true,"
                    + "\"removeDate\":true,\"removeSubject\":false},\"attachments\":\"remove\",\"inlineImages\":\"keep\"}}}";

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
        final String username = "email-" + UUID.randomUUID() + "@example.com";
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

    private HttpResponse<String> redact(final String query, final String contentType, final String accept,
                                        final byte[] body) throws Exception {
        return send(authenticated("/api/filter" + query).header("Content-Type", contentType)
                .header("Accept", accept).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    private HttpResponse<String> redact(final String query, final String contentType, final byte[] body) throws Exception {
        return redact(query, contentType, EML, body);
    }

    @Test
    @DisplayName("An .eml message is redacted synchronously and returned as message/rfc822")
    void anEmlMessageIsRedacted() throws Exception {
        final HttpResponse<String> response = redact("?p=ssn-only&async=false", EML, EmailMessages.eml("SSN " + SSN, TEXT));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(EML));
        assertTrue(response.headers().firstValue("X-Document-Id").isPresent());
        assertFalse(response.body().contains(SSN), response.body());
        assertTrue(response.body().contains("{{{REDACTED-ssn}}}"), response.body());
        // The default config.email removes technical headers and keeps the date.
        assertFalse(response.body().contains("X-Mailer"), response.body());
        assertTrue(response.body().contains("Date:"), response.body());
    }

    @Test
    @DisplayName("An Outlook message is redacted synchronously and returned as message/rfc822")
    void anOutlookMessageIsRedactedToEml() throws Exception {
        final HttpResponse<String> response = redact("?p=ssn-only&async=false", MSG, "*/*", EmailMessages.msg("Hello", TEXT));

        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith(EML));
        assertFalse(response.body().contains(SSN), response.body());
        assertTrue(response.body().contains("{{{REDACTED-ssn}}}"), response.body());
        assertTrue(response.body().contains("Subject: Hello"), response.body());
    }

    @Test
    @DisplayName("Queued .eml and .msg messages complete and download as message/rfc822")
    void queuedMessagesComplete() throws Exception {
        for (final String[] input : new String[][]{{EML, "eml"}, {MSG, "msg"}}) {
            final byte[] body = EML.equals(input[0]) ? EmailMessages.eml("Hello", TEXT) : EmailMessages.msg("Hello", TEXT);
            final HttpResponse<String> accepted = redact("?p=ssn-only&filename=note." + input[1], input[0], body);
            assertEquals(202, accepted.statusCode(), accepted.body());
            final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();

            assertEquals("COMPLETE", awaitStatus(documentId).get("status").getAsString());

            final HttpResponse<String> result = send(authenticated("/api/documents/" + documentId).GET().build());
            assertEquals(200, result.statusCode());
            assertTrue(result.headers().firstValue("Content-Type").orElseThrow().startsWith(EML));
            assertFalse(result.body().contains(SSN), result.body());
            assertTrue(result.body().contains("{{{REDACTED-ssn}}}"), result.body());
        }
    }

    private JsonObject awaitStatus(final String documentId) throws Exception {
        JsonObject status = null;
        for (int attempt = 0; attempt < 150; attempt++) {
            Thread.sleep(200);
            final HttpResponse<String> polled = send(authenticated("/api/documents/" + documentId + "/status").GET().build());
            status = gson.fromJson(polled.body(), JsonObject.class);
            final String value = status.get("status").getAsString();
            if ("COMPLETE".equals(value) || "FAILED".equals(value)) {
                return status;
            }
        }
        return status;
    }

    @Test
    @DisplayName("Two policies with different config.email settings give different output through one instance")
    void eachPolicysEmailSettingsApply() throws Exception {
        savePolicy("strict-email", STRICT_EMAIL_POLICY);
        final byte[] message = EmailMessages.emlWithAttachment(TEXT, "report.txt", "text/plain",
                "Quarterly report".getBytes(StandardCharsets.UTF_8));

        final HttpResponse<String> defaults = redact("?p=ssn-only&async=false", EML, message);
        final HttpResponse<String> strict = redact("?p=strict-email&async=false", EML, message);
        assertEquals(200, defaults.statusCode(), defaults.body());
        assertEquals(200, strict.statusCode(), strict.body());

        assertTrue(defaults.body().contains("Date:"), defaults.body());
        assertFalse(defaults.body().contains("report.txt"), defaults.body());
        assertFalse(strict.body().contains("Date:"), strict.body());
        assertFalse(strict.body().contains("report.txt"), strict.body());
        assertFalse(defaults.body().contains(SSN) || strict.body().contains(SSN));
    }

    @Test
    @DisplayName("A legacy Word document declared as Outlook, and a body that is not MIME declared as message/rfc822, are refused with 415")
    void mislabelledBodiesAreRefused() throws Exception {
        assertEquals(415, redact("?p=ssn-only&async=false", MSG, EmailMessages.legacyWordDocument()).statusCode());
        assertEquals(415, redact("?p=ssn-only", MSG, EmailMessages.legacyWordDocument()).statusCode(), "queued too");
        final byte[] notMime = "Just some notes.\nNot a message.".getBytes(StandardCharsets.UTF_8);
        assertEquals(415, redact("?p=ssn-only&async=false", EML, notMime).statusCode());
        assertEquals(415, redact("?p=ssn-only", EML, notMime).statusCode(), "queued too");
    }

    @Test
    @DisplayName("An Accept other than message/rfc822 or */* is refused with 406")
    void anUnsupportedAcceptIsRefused() throws Exception {
        assertEquals(406, redact("?p=ssn-only&async=false", MSG, MSG, EmailMessages.msg("Hello", TEXT)).statusCode());
        assertEquals(406, redact("?p=ssn-only&async=false", EML, "text/plain", EmailMessages.eml("Hello", TEXT)).statusCode());
    }

    @Test
    @DisplayName("A message over the attachment count or nesting limit is refused with 413")
    void limitsAreEnforced() throws Exception {
        final HttpResponse<String> tooMany = redact("?p=ssn-only&async=false", EML, EmailMessages.emlWithAttachments(101));
        assertEquals(413, tooMany.statusCode(), tooMany.body());
        assertTrue(tooMany.body().contains("EMAIL_MAX_ATTACHMENTS"), tooMany.body());

        final HttpResponse<String> tooDeep = redact("?p=ssn-only", EML, EmailMessages.nested(11, TEXT));
        assertEquals(413, tooDeep.statusCode(), tooDeep.body());
        assertTrue(tooDeep.body().contains("EMAIL_MAX_NESTING_DEPTH"), tooDeep.body());
    }

    @Test
    @DisplayName("An encrypted message is refused with 400 and no message, queued or not")
    void anEncryptedMessageIsRefused() throws Exception {
        for (final String query : new String[]{"?p=ssn-only&async=false", "?p=ssn-only"}) {
            final HttpResponse<String> refused = redact(query, EML, EmailMessages.encrypted());
            assertEquals(400, refused.statusCode(), query + ": " + refused.body());
            assertTrue(refused.body().contains("encrypted"), refused.body());
            assertFalse(refused.body().contains("Subject:"), refused.body());
        }
    }

    @Test
    @DisplayName("A PGP-encrypted body is refused with 400, or fails its job when queued")
    void aPgpEncryptedBodyIsRefusedWhenRedacted() throws Exception {
        final HttpResponse<String> refused = redact("?p=ssn-only&async=false", EML, EmailMessages.pgpEncrypted());
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("PGP"), refused.body());

        final HttpResponse<String> accepted = redact("?p=ssn-only", EML, EmailMessages.pgpEncrypted());
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();
        final JsonObject status = awaitStatus(documentId);
        assertEquals("FAILED", status.get("status").getAsString());
        assertTrue(status.toString().contains("PGP"), status.toString());
    }

    @Test
    @DisplayName("A queued message is redacted with the config.email of the policy version pinned when it was accepted")
    void aQueuedMessageUsesThePinnedEmailSettings() throws Exception {
        savePolicy("pinned-email", SSN_POLICY);
        final HttpResponse<String> accepted = redact("?p=pinned-email", EML, EmailMessages.eml("Hello", TEXT));
        assertEquals(202, accepted.statusCode(), accepted.body());
        final String documentId = gson.fromJson(accepted.body(), JsonObject.class).get("documentId").getAsString();

        // The worker polls every 5 seconds, so this lands before the job is claimed.
        final HttpResponse<String> edited = send(authenticated("/api/policies/pinned-email")
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(STRICT_EMAIL_POLICY)).build());
        assertEquals(200, edited.statusCode(), edited.body());

        assertEquals("COMPLETE", awaitStatus(documentId).get("status").getAsString());
        final HttpResponse<String> result = send(authenticated("/api/documents/" + documentId).GET().build());
        assertTrue(result.body().contains("Date:"), "the pinned version keeps the date: " + result.body());

        final HttpResponse<String> now = redact("?p=pinned-email&async=false", EML, EmailMessages.eml("Hello", TEXT));
        assertFalse(now.body().contains("Date:"), "the edited version removes it: " + now.body());
    }

    @Test
    @DisplayName("A message with a PDF attachment under attachments: redact is redacted, and returns its PDF slot")
    void aPdfAttachmentUnderRedactReturnsItsSlot() throws Exception {
        savePolicy("redact-attachments", SSN_POLICY.substring(0, SSN_POLICY.length() - 1)
                + ",\"config\":{\"email\":{\"attachments\":\"redact\"}}}");
        // Not a readable PDF, so Phileas removes it; it is still taken for a PDF, so it takes a slot.
        final byte[] message = EmailMessages.emlWithAttachment(TEXT, "scan.pdf", "application/pdf",
                "%PDF-1.7 damaged".getBytes(StandardCharsets.US_ASCII));

        // More requests than PDF_MAX_CONCURRENT allows at once (2 by default), one after another.
        for (int i = 0; i < 3; i++) {
            final HttpResponse<String> response = redact("?p=redact-attachments&async=false", EML, message);
            assertEquals(200, response.statusCode(), response.body());
            assertFalse(response.body().contains(SSN), response.body());
            assertFalse(response.body().contains("scan.pdf"), response.body());
        }
    }

    // A CSV, an RTF, and a plain-text attachment, each holding the SSN.
    private static EmailMessages.Attachment[] textAttachments() {
        return new EmailMessages.Attachment[]{
                new EmailMessages.Attachment("patients.csv", "text/csv; charset=UTF-8",
                        ("Name,SSN\nAvery Example," + SSN + "\n").getBytes(StandardCharsets.UTF_8)),
                new EmailMessages.Attachment("letter.rtf", "application/rtf", RtfDocuments.rtf(TEXT)),
                new EmailMessages.Attachment("notes.txt", "text/plain; charset=UTF-8", TEXT.getBytes(StandardCharsets.UTF_8))};
    }

    /** The attachments of a returned message, by filename. */
    private static Map<String, MimeBodyPart> attachmentsOf(final String message) throws Exception {
        final MimeMessage parsed = new MimeMessage(EmailMessages.SESSION,
                new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)));
        final MimeMultipart multipart = (MimeMultipart) parsed.getContent();
        final Map<String, MimeBodyPart> attachments = new LinkedHashMap<>();
        for (int i = 0; i < multipart.getCount(); i++) {
            final MimeBodyPart part = (MimeBodyPart) multipart.getBodyPart(i);
            if (part.getFileName() != null) {
                attachments.put(part.getFileName(), part);
            }
        }
        return attachments;
    }

    private static byte[] contentOf(final MimeBodyPart part) throws Exception {
        try (InputStream in = part.getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("CSV, RTF, and plain-text attachments under attachments: redact are redacted and keep their filename and type")
    void textAttachmentsUnderRedactAreRedacted() throws Exception {
        savePolicy("redact-attachments", SSN_POLICY.substring(0, SSN_POLICY.length() - 1)
                + ",\"config\":{\"email\":{\"attachments\":\"redact\"}}}");

        final HttpResponse<String> response = redact("?p=redact-attachments&async=false", EML,
                EmailMessages.emlWithAttachments(TEXT, textAttachments()));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("uninspected-attachments=0, uninspected-inline-images=0, removed-attachments=0, "
                + "removed-inline-images=0, dropped-signatures=0",
                response.headers().firstValue("X-Philter-Email-Report").orElseThrow());

        final Map<String, MimeBodyPart> attachments = attachmentsOf(response.body());
        assertEquals(List.of("patients.csv", "letter.rtf", "notes.txt"), List.copyOf(attachments.keySet()));

        assertTrue(attachments.get("patients.csv").isMimeType("text/csv"), attachments.get("patients.csv").getContentType());
        assertEquals("Name,SSN\nAvery Example,{{{REDACTED-ssn}}}\n",
                new String(contentOf(attachments.get("patients.csv")), StandardCharsets.UTF_8));

        assertTrue(attachments.get("letter.rtf").isMimeType("application/rtf"), attachments.get("letter.rtf").getContentType());
        final byte[] rtf = contentOf(attachments.get("letter.rtf"));
        assertFalse(new String(rtf, StandardCharsets.ISO_8859_1).contains(SSN));
        assertTrue(RtfDocuments.text(rtf).contains("The patient's SSN is {{{REDACTED-ssn}}}."), RtfDocuments.text(rtf));

        assertTrue(attachments.get("notes.txt").isMimeType("text/plain"), attachments.get("notes.txt").getContentType());
        assertEquals("The patient's SSN is {{{REDACTED-ssn}}}.",
                new String(contentOf(attachments.get("notes.txt")), StandardCharsets.UTF_8));

        // Not valid in the charset it declares, or in a charset Java does not have: removed and counted, not returned.
        for (final EmailMessages.Attachment attachment : List.of(
                new EmailMessages.Attachment("legacy.txt", "text/plain; charset=US-ASCII",
                        ("SSN " + SSN + " caf\u00e9").getBytes(StandardCharsets.ISO_8859_1)),
                new EmailMessages.Attachment("odd.txt", "text/plain; charset=x-no-such-charset",
                        TEXT.getBytes(StandardCharsets.UTF_8)))) {
            final HttpResponse<String> unreadable = redact("?p=redact-attachments&async=false", EML,
                    EmailMessages.emlWithAttachments(TEXT, attachment));
            assertEquals(200, unreadable.statusCode(), unreadable.body());
            assertEquals("uninspected-attachments=0, uninspected-inline-images=0, removed-attachments=1, "
                    + "removed-inline-images=0, dropped-signatures=0",
                    unreadable.headers().firstValue("X-Philter-Email-Report").orElseThrow(), attachment.fileName());
            assertTrue(attachmentsOf(unreadable.body()).isEmpty(), unreadable.body());
            assertFalse(unreadable.body().contains(SSN), unreadable.body());
        }
    }

    @Test
    @DisplayName("CSV, RTF, and plain-text attachments under attachments: keep are returned uninspected")
    void textAttachmentsUnderKeepAreUninspected() throws Exception {
        savePolicy("keep-attachments", SSN_POLICY.substring(0, SSN_POLICY.length() - 1)
                + ",\"config\":{\"email\":{\"attachments\":\"keep\"}}}");
        final HttpResponse<String> response = redact("?p=keep-attachments&async=false", EML,
                EmailMessages.emlWithAttachments(TEXT, textAttachments()));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("uninspected-attachments=3, uninspected-inline-images=0, removed-attachments=0, "
                + "removed-inline-images=0, dropped-signatures=0",
                response.headers().firstValue("X-Philter-Email-Report").orElseThrow());

        final Map<String, MimeBodyPart> attachments = attachmentsOf(response.body());
        final EmailMessages.Attachment[] sent = textAttachments();
        assertEquals(sent.length, attachments.size());
        for (final EmailMessages.Attachment attachment : sent) {
            assertArrayEquals(attachment.content(), contentOf(attachments.get(attachment.fileName())), attachment.fileName());
        }
    }

    @Test
    @DisplayName("A policy without config.email removes attachments and inline images")
    void attachmentsAndInlineImagesAreRemovedByDefault() throws Exception {
        final HttpResponse<String> response = redact("?p=ssn-only&async=false", EML,
                EmailMessages.emlWithInlineImageAndAttachment(TEXT, "report.txt", "text/plain",
                        "Quarterly report".getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("uninspected-attachments=0, uninspected-inline-images=0, removed-attachments=1, "
                + "removed-inline-images=1, dropped-signatures=0",
                response.headers().firstValue("X-Philter-Email-Report").orElseThrow());

        final MimeMessage returned = new MimeMessage(EmailMessages.SESSION,
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
        final List<String> types = new ArrayList<>();
        collectTypes(returned, types);
        assertTrue(types.stream().noneMatch(t -> t.startsWith("image/")), types.toString());
        assertTrue(types.stream().anyMatch(t -> t.startsWith("text/html")), "the body is kept: " + types);
        assertFalse(response.body().contains("report.txt"), response.body());
        // The image's reference is pointed at about:blank.
        assertFalse(response.body().contains("cid:logo@example.com"), response.body());
        assertTrue(response.body().contains("about:blank"), response.body());
        assertTrue(response.body().contains("{{{REDACTED-ssn}}}"), response.body());
        assertFalse(response.body().contains(SSN), response.body());
    }

    @Test
    @DisplayName("Under inlineImages: keep, an inline image is returned uninspected and not counted as removed")
    void inlineImagesUnderKeepAreUninspected() throws Exception {
        savePolicy("keep-images", SSN_POLICY.substring(0, SSN_POLICY.length() - 1)
                + ",\"config\":{\"email\":{\"inlineImages\":\"keep\"}}}");
        final HttpResponse<String> response = redact("?p=keep-images&async=false", EML,
                EmailMessages.emlWithInlineImageAndAttachment(TEXT, "report.txt", "text/plain",
                        "Quarterly report".getBytes(StandardCharsets.UTF_8)));
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("uninspected-attachments=0, uninspected-inline-images=1, removed-attachments=1, "
                + "removed-inline-images=0, dropped-signatures=0",
                response.headers().firstValue("X-Philter-Email-Report").orElseThrow());

        final List<String> types = new ArrayList<>();
        collectTypes(new MimeMessage(EmailMessages.SESSION,
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8))), types);
        assertTrue(types.stream().anyMatch(t -> t.startsWith("image/png")), types.toString());
        assertTrue(response.body().contains("cid:logo@example.com"), response.body());
    }

    private static void collectTypes(final Part part, final List<String> types) throws Exception {
        types.add(part.getContentType().toLowerCase());
        if (part.getContent() instanceof Multipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                collectTypes(multipart.getBodyPart(i), types);
            }
        }
    }

    @Test
    @DisplayName("An unencoded email sent as text/plain is redacted as text")
    void anUnencodedEmailSentAsTextIsRedacted() throws Exception {
        final HttpResponse<String> response = redact("?p=ssn-only", "text/plain", "text/plain", EmailMessages.eml("Hello", TEXT));
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().contains(SSN), response.body());
        assertTrue(response.body().contains("{{{REDACTED-ssn}}}"), response.body());
    }

    @Test
    @DisplayName("An encoded or multipart email sent as text/plain is refused with 415 by filter and explain")
    void anEncodedEmailSentAsTextIsRefused() throws Exception {
        final byte[][] encoded = {EmailMessages.emlEncoded("base64", TEXT), EmailMessages.emlEncoded("quoted-printable", TEXT),
                EmailMessages.emlWithAttachment(TEXT, "notes.txt", "text/plain", TEXT.getBytes(StandardCharsets.UTF_8))};
        for (final byte[] body : encoded) {
            final HttpResponse<String> filtered = redact("?p=ssn-only", "text/plain", "text/plain", body);
            assertEquals(415, filtered.statusCode(), filtered.body());
            assertTrue(filtered.body().contains("message/rfc822"), filtered.body());
            final HttpResponse<String> explained = send(authenticated("/api/explain?p=ssn-only").header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
            assertEquals(415, explained.statusCode(), explained.body());
        }
    }

    @Test
    @DisplayName("Redactions in an email message are recorded in the ledger")
    void redactionsAreLedgered() throws Exception {
        final String context = "email-" + UUID.randomUUID().toString().substring(0, 8);
        final HttpResponse<String> created = send(authenticated("/api/contexts").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"" + context + "\",\"ledger\":true}")).build());
        assertTrue(created.statusCode() == 200 || created.statusCode() == 201, created.body());

        final HttpResponse<String> response = redact("?p=ssn-only&c=" + context + "&async=false&filename=a.eml", EML,
                EmailMessages.eml("Hello", TEXT));
        assertEquals(200, response.statusCode(), response.body());

        final String documentId = response.headers().firstValue("X-Document-Id").orElseThrow();
        final HttpResponse<String> ledger = send(authenticated("/api/ledger/" + documentId).GET().build());
        assertEquals(200, ledger.statusCode(), ledger.body());
        final JsonObject chain = gson.fromJson(ledger.body(), JsonObject.class);
        assertTrue(chain.get("hashChainValid").getAsBoolean(), ledger.body());
        assertTrue(ledger.body().contains("\"type\":\"ssn\""), ledger.body());
    }

}
