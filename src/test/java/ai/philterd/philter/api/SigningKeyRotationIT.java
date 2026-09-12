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
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bson.types.ObjectId;
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
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rotating the signing key over HTTP. The properties that matter are what happens to the key that
 * was replaced: it stays fetchable, reports itself inactive, and still verifies what it signed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class SigningKeyRotationIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

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
    private ai.philterd.philter.data.services.SigningKeyDataService signingKeyDataService;

    private final Gson gson = new Gson();

    private HttpClient httpClient;
    private String baseUrl;
    private String adminKey;
    private ObjectId adminUserId;

    @BeforeEach
    void setUp() {

        httpClient = HttpClient.newHttpClient();
        baseUrl = "http://localhost:" + environment.getRequiredProperty("local.server.port", Integer.class);

        AdminAccessConfig.setOverrideForTesting(true);

        adminUserId = createUser("admin");
        adminKey = createKey(adminUserId, ApiKeyScope.all());

    }

    @AfterEach
    void tearDown() {
        AdminAccessConfig.setOverrideForTesting(null);
        httpClient.close();
    }

    private ObjectId createUser(final String role) {
        final String username = "rotation-" + UUID.randomUUID() + "@example.com";
        final ServiceResponse created = userService.createUser("req", username, "password", role,
                policyDataService, contextDataService, "test");
        assertTrue(created.isSuccessful(), "the test user must be created");
        return userService.findByUsername(username).getId();
    }

    private String createKey(final ObjectId userId, final Set<String> scopes) {
        final ServiceResponse response = apiKeyDataService.createApiKey("req", userId, "test", scopes);
        assertTrue(response.isSuccessful(), "the API key must be created");
        return response.getMessage();
    }

    private HttpResponse<String> rotate(final String apiKey) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/signing-key/regenerate"))
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObject getJson(final String path) throws Exception {
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path);
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private String activeKeyId() throws Exception {
        return getJson("/api/signing-key").get("keyId").getAsString();
    }

    @Test
    @DisplayName("Rotation replaces the active key and names the new one in the response")
    void rotationNamesTheKeyThatIsNowActive() throws Exception {

        final String before = activeKeyId();

        final HttpResponse<String> response = rotate(adminKey);
        assertEquals(200, response.statusCode());

        final String named = gson.fromJson(response.body(), JsonObject.class).get("keyId").getAsString();

        assertNotEquals(before, named, "rotation must publish a different key");
        assertEquals(named, activeKeyId(),
                "the key the response names must be the one that is actually active");

    }

    @Test
    @DisplayName("The superseded key stays retrievable, reports itself inactive, and still verifies")
    void theSupersededKeyOutlivesTheRotation() throws Exception {

        final String superseded = activeKeyId();

        // Signed before the rotation, by the key that is about to be replaced.
        final byte[] signed = "ledger entry signed under the old key".getBytes(StandardCharsets.UTF_8);
        final byte[] signature = sign(signed, superseded);

        assertEquals(200, rotate(adminKey).statusCode());

        final JsonObject retained = getJson("/api/signing-key/" + superseded);
        assertEquals(superseded, retained.get("keyId").getAsString());
        assertFalse(retained.get("active").getAsBoolean(), "the replaced key must report itself inactive");

        assertTrue(verify(signed, signature, publicKeyFrom(retained)),
                "a signature made before the rotation must still verify against the retained key");

    }

    @Test
    @DisplayName("A rotation is audited against the user, naming the API key that carried it")
    void aRotationIsAuditedAgainstTheUser() throws Exception {

        assertEquals(200, rotate(adminKey).statusCode());

        final HttpResponse<String> audit = httpClient.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/audit?event=signing_key_regenerated"))
                        .header("Authorization", "Bearer " + adminKey).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, audit.statusCode());

        JsonObject event = null;
        for (final var candidate : gson.fromJson(audit.body(), JsonObject.class).getAsJsonArray("events")) {
            if (adminUserId.toString().equals(candidate.getAsJsonObject().get("apiKeyId").getAsString())) {
                event = candidate.getAsJsonObject();
            }
        }

        assertNotNull(event, "the rotation must be audited against the acting user: " + audit.body());
        assertTrue(event.get("details").getAsString().contains("source: api"),
                "the details must say how the rotation was requested: " + event);
        assertTrue(event.get("details").getAsString().contains("api_key: "),
                "the details must name the key that carried it: " + event);

    }

    @Test
    @DisplayName("Reading a public key still needs no credentials; rotating one does")
    void theSubtreeIsPublicForReadsOnly() throws Exception {

        // Verifiers are not the operator and hold no key of theirs, so the reads must stay open.
        assertEquals(200, unauthenticated("GET", "/api/signing-key").statusCode());
        assertEquals(200, unauthenticated("GET", "/api/signing-key/" + activeKeyId()).statusCode());

        final String before = activeKeyId();
        assertEquals(401, unauthenticated("POST", "/api/signing-key/regenerate").statusCode(),
                "rotation shares the prefix but is not a read");
        assertEquals(before, activeKeyId(), "an unauthenticated request must not rotate the key");

    }

    private HttpResponse<String> unauthenticated(final String method, final String path) throws Exception {
        return httpClient.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("A key without signing:write cannot rotate")
    void aKeyWithoutTheScopeCannotRotate() throws Exception {

        final String redactOnly = createKey(adminUserId, Set.of(ApiKeyScope.REDACT.getScope()));
        final String before = activeKeyId();

        final HttpResponse<String> response = rotate(redactOnly);

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("signing:write"),
                "the refusal should name the missing scope: " + response.body());
        assertEquals(before, activeKeyId(), "a refused request must not rotate the key");

    }

    @Test
    @DisplayName("A non-administrator cannot rotate")
    void aNonAdministratorCannotRotate() throws Exception {

        final String regularKey = createKey(createUser("user"), ApiKeyScope.all());
        final String before = activeKeyId();

        assertEquals(403, rotate(regularKey).statusCode());
        assertEquals(before, activeKeyId(), "a refused request must not rotate the key");

    }

    private byte[] sign(final byte[] content, final String expectedKeyId) throws Exception {
        assertEquals(expectedKeyId, signingKeyDataService.getActiveKeyId(), "signing must use the key under test");
        final Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(signingKeyDataService.currentSigningKey().privateKey());
        signer.update(content);
        return signer.sign();
    }

    private static boolean verify(final byte[] content, final byte[] signature, final PublicKey publicKey)
            throws Exception {
        final Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(publicKey);
        verifier.update(content);
        return verifier.verify(signature);
    }

    private static PublicKey publicKeyFrom(final JsonObject key) throws Exception {
        final String pem = key.get("pem").getAsString()
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

}
