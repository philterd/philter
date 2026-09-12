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

import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The signing truth table over real HTTP: the admin setting is a floor, so a request can add
 * signing but never remove it. The controller unit tests mock the decision away, so only this
 * exercises the real composition of setting, parameter, and signature.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class SignOnRequestIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final String SIGNATURE_HEADER = "X-Philter-Signature";

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

    @Autowired
    private AdminSettingsDataService adminSettingsDataService;

    private HttpClient httpClient;
    private String baseUrl;
    private String apiKey;
    private ObjectId userId;

    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        final String username = "sign-on-request-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", "admin",
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");

        userId = userService.findByUsername(username).getId();
        apiKey = apiKeyDataService.createApiKey("req", userId, "test").getMessage();

        // ph-eye is not running here, so use a policy needing only the built-in SSN filter.
        assertTrue(policyDataService.create("req", userId, """
                { "identifiers": { "ssn": { "ssnFilterStrategies": [ { "strategy": "REDACT" } ] } } }
                """, "desc", "notes", "ssn-only", "test").isSuccessful());

        signingEnabled(false);

    }

    @AfterEach
    void tearDown() {
        signingEnabled(false);
        httpClient.close();
    }

    private void signingEnabled(final boolean enabled) {
        adminSettingsDataService.saveSigningEnabled(userId, enabled);
    }

    /** Redacts a fixed document, returning the response so the signature header can be inspected. */
    private HttpResponse<String> filter(final String query) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only" + query))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> explain(final String query) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/explain?p=ssn-only" + query))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("His SSN was 123-45-6789."))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ----- the admin setting is a floor -----

    @Test
    @DisplayName("Setting on: every response is signed, including one that asks not to be")
    void theSettingOnCannotBeOverriddenDownwards() throws Exception {

        signingEnabled(true);

        for (final String query : new String[]{"", "&sign=true", "&sign=false"}) {
            final HttpResponse<String> response = filter(query);
            assertEquals(200, response.statusCode());
            assertNotNull(response.headers().firstValue(SIGNATURE_HEADER).orElse(null),
                    "a request must not be able to suppress signing; query was '" + query + "'");
        }

    }

    @Test
    @DisplayName("Setting off: a request that asks is signed, one that does not is not")
    void theSettingOffLetsARequestAddSigning() throws Exception {

        assertNull(filter("").headers().firstValue(SIGNATURE_HEADER).orElse(null),
                "an unasked response must not be signed");
        assertNull(filter("&sign=false").headers().firstValue(SIGNATURE_HEADER).orElse(null),
                "sign=false must not be signed when the setting is off");
        assertNotNull(filter("&sign=true").headers().firstValue(SIGNATURE_HEADER).orElse(null),
                "sign=true must be signed even when the setting is off");

    }

    @Test
    @DisplayName("The parameter applies to /api/explain as well")
    void explainHonorsTheParameter() throws Exception {

        assertNull(explain("").headers().firstValue(SIGNATURE_HEADER).orElse(null));
        assertNotNull(explain("&sign=true").headers().firstValue(SIGNATURE_HEADER).orElse(null));

        signingEnabled(true);
        assertNotNull(explain("&sign=false").headers().firstValue(SIGNATURE_HEADER).orElse(null),
                "the floor applies to explain too");

    }

    @Test
    @DisplayName("A PDF request asking to be signed is refused, not answered unsigned")
    void aPdfRequestAskingForASignatureIsRefused() throws Exception {

        // The control: the same request without the parameter is accepted, so the 400 below is
        // attributable to the refusal and not to the document.
        assertEquals(202, pdf("").statusCode(), "the PDF itself must be acceptable");

        assertEquals(400, pdf("&sign=true").statusCode(),
                "an unsigned response to a caller that asked for a signature is the failure this avoids");

        // Even with the setting on, since PDF responses are not signed either way.
        signingEnabled(true);
        assertEquals(400, pdf("&sign=true").statusCode());
        assertEquals(202, pdf("&sign=false").statusCode(), "sign=false asks for nothing");

    }

    private HttpResponse<String> pdf(final String query) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/filter?p=ssn-only" + query))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/pdf")
                .header("Accept", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(onePagePdf()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** A real one-page PDF, so a rejected request is rejected for the reason under test. */
    private static byte[] onePagePdf() throws Exception {
        try (final PDDocument document = new PDDocument();
             final ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    // ----- the signature is the same signature -----

    @Test
    @DisplayName("A signature asked for by a request verifies against GET /api/signing-key")
    void aRequestedSignatureVerifiesAgainstThePublishedKey() throws Exception {

        final HttpResponse<String> requested = filter("&sign=true");
        final String jwt = requested.headers().firstValue(SIGNATURE_HEADER).orElseThrow();

        assertTrue(verify(jwt, publishedKey()), "a requested signature must verify like any other");

        // Identical in form to one the admin setting produced: same algorithm, same claims.
        signingEnabled(true);
        final String mandated = filter("").headers().firstValue(SIGNATURE_HEADER).orElseThrow();

        assertTrue(verify(mandated, publishedKey()));
        assertEquals(header(mandated), header(requested.headers().firstValue(SIGNATURE_HEADER).orElseThrow()),
                "both must carry the same JWT header");
        assertEquals(claimNames(mandated), claimNames(jwt), "both must carry the same claims");

    }

    private PublicKey publishedKey() throws Exception {

        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/signing-key")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());

        final String pem = new Gson().fromJson(response.body(), JsonObject.class).get("pem").getAsString()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        return KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));

    }

    /** Verifies the compact JWT the same way a consumer would. See SigningServiceIT. */
    private static boolean verify(final String jwt, final PublicKey publicKey) throws Exception {
        final String[] parts = jwt.split("\\.");
        final Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
    }

    private static String header(final String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
    }

    private static java.util.Set<String> claimNames(final String jwt) {
        final String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        return new Gson().fromJson(payload, JsonObject.class).keySet();
    }

}
