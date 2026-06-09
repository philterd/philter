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
package ai.philterd.philter.services.signing;

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SigningServiceTest {

    @Mock
    private SigningKeyDataService signingKeyDataService;

    @Mock
    private AdminSettingsDataService adminSettingsDataService;

    private KeyPair keyPair;
    private SigningService signingService;

    @BeforeEach
    void setUp() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = kpg.generateKeyPair();

        when(signingKeyDataService.getPrivateKey()).thenReturn(keyPair.getPrivate());

        signingService = new SigningService(signingKeyDataService, adminSettingsDataService);
    }

    @Test
    void signProducesThreePartJwt() throws Exception {
        final String jwt = signingService.sign("hello world", "default", 1, UUID.randomUUID().toString());

        assertNotNull(jwt);
        final String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
    }

    @Test
    void headerIsEs256() throws Exception {
        final String jwt = signingService.sign("hello world", "default", 1, UUID.randomUUID().toString());
        final String headerJson = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
        assertTrue(headerJson.contains("\"alg\":\"ES256\""), "header must contain ES256 alg");
        assertTrue(headerJson.contains("\"typ\":\"JWT\""), "header must contain JWT typ");
    }

    @Test
    void payloadContainsBodyHashAndPolicy() throws Exception {
        final String body = "The quick brown fox";
        final String jwt = signingService.sign(body, "my-policy", 7, "resp-id-123");
        final String payloadJson = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("\"policyName\":\"my-policy\""), "payload must contain policyName");
        assertTrue(payloadJson.contains("\"policyVersion\":7"), "payload must contain policyVersion");
        assertTrue(payloadJson.contains("\"documentId\":\"resp-id-123\""), "payload must contain documentId");
        assertTrue(payloadJson.contains("\"bodyHash\":"), "payload must contain bodyHash");
        assertTrue(payloadJson.contains("\"iat\":"), "payload must contain iat");
    }

    @Test
    void signatureIsVerifiableWithPublicKey() throws Exception {
        final String body = "sensitive redacted text";
        final String jwt = signingService.sign(body, "default", 1, UUID.randomUUID().toString());

        final String[] parts = jwt.split("\\.");
        final String signingInput = parts[0] + "." + parts[1];
        final byte[] p1363Sig = Base64.getUrlDecoder().decode(parts[2]);

        final byte[] derSig = p1363ToDer(p1363Sig);

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        assertTrue(verifier.verify(derSig), "signature must verify with the public key");
    }

    @Test
    void differentBodyProducesDifferentPayload() throws Exception {
        final String id = UUID.randomUUID().toString();
        final String jwt1 = signingService.sign("body one", "default", 1, id);
        final String jwt2 = signingService.sign("body two", "default", 1, id);
        final String payload1 = jwt1.split("\\.")[1];
        final String payload2 = jwt2.split("\\.")[1];
        assertFalse(payload1.equals(payload2), "different bodies must produce different payloads");
    }

    @Test
    void derToP1363RoundTripsForKnownVector() {
        final byte[] r = new byte[32];
        final byte[] s = new byte[32];
        r[0] = 0x01;
        s[0] = 0x7F;

        final byte[] der = buildDer(r, s);
        final byte[] p1363 = SigningService.derToP1363(der);

        assertEquals(64, p1363.length, "P1363 must be 64 bytes");
        assertEquals(r[0], p1363[0], "first byte of R must match");
        assertEquals(s[0], p1363[32], "first byte of S must match");
    }

    @Test
    void derToP1363StripsLeadingZeroFromR() {
        // DER pads R with a leading 0x00 when its high bit is set; P1363 must strip it
        final byte[] rWithPad = new byte[33];
        rWithPad[1] = (byte) 0xFF;
        final byte[] s = new byte[32];
        s[0] = 0x01;

        final byte[] der = buildDer(rWithPad, s);
        final byte[] p1363 = SigningService.derToP1363(der);

        assertEquals(64, p1363.length, "P1363 must be 64 bytes");
        assertEquals((byte) 0xFF, p1363[0], "leading zero must be stripped from R");
    }

    @Test
    void signatureDoesNotVerifyForModifiedBody() throws Exception {
        final String original = "original redacted text";
        final String jwt = signingService.sign(original, "default", 1, UUID.randomUUID().toString());

        final String[] parts = jwt.split("\\.");
        final String signingInput = parts[0] + "." + parts[1];
        final byte[] p1363Sig = Base64.getUrlDecoder().decode(parts[2]);
        final byte[] derSig = p1363ToDer(p1363Sig);

        // Verify against a *different* body — the signing input covers the original hash,
        // so the signature must not validate when applied to a different payload.
        final String tampered = "tampered content";
        final String[] tamperedParts = signingService.sign(tampered, "default", 1, UUID.randomUUID().toString()).split("\\.");
        final String tamperedSigningInput = tamperedParts[0] + "." + tamperedParts[1];

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(tamperedSigningInput.getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(derSig), "original signature must not verify against a different body");
    }

    @Test
    void signatureDoesNotVerifyAgainstDifferentKeypair() throws Exception {
        final String jwt = signingService.sign("some redacted text", "default", 1, UUID.randomUUID().toString());

        final String[] parts = jwt.split("\\.");
        final String signingInput = parts[0] + "." + parts[1];
        final byte[] p1363Sig = Base64.getUrlDecoder().decode(parts[2]);
        final byte[] derSig = p1363ToDer(p1363Sig);

        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final java.security.PublicKey wrongPublicKey = kpg.generateKeyPair().getPublic();

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(wrongPublicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        assertFalse(verifier.verify(derSig), "signature must not verify against a different public key");
    }

    @Test
    void isSigningEnabledReturnsFalseWhenSettingsNull() {
        when(adminSettingsDataService.findAdminSettings()).thenReturn(null);
        assertFalse(signingService.isSigningEnabled());
    }

    @Test
    void isSigningEnabledReturnsFalseWhenDisabled() {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setSigningEnabled(false);
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings);
        assertFalse(signingService.isSigningEnabled());
    }

    @Test
    void isSigningEnabledReturnsTrueWhenEnabled() {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setSigningEnabled(true);
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings);
        assertTrue(signingService.isSigningEnabled());
    }

    // --- helpers ---

    private static byte[] buildDer(final byte[] r, final byte[] s) {
        final int totalLen = 2 + r.length + 2 + s.length;
        final byte[] der = new byte[2 + totalLen];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) totalLen;
        der[i++] = 0x02;
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length);
        i += r.length;
        der[i++] = 0x02;
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }

    private static byte[] p1363ToDer(final byte[] p1363) {
        byte[] r = Arrays.copyOfRange(p1363, 0, 32);
        byte[] s = Arrays.copyOfRange(p1363, 32, 64);

        if ((r[0] & 0x80) != 0) {
            final byte[] padded = new byte[33];
            System.arraycopy(r, 0, padded, 1, 32);
            r = padded;
        }
        if ((s[0] & 0x80) != 0) {
            final byte[] padded = new byte[33];
            System.arraycopy(s, 0, padded, 1, 32);
            s = padded;
        }

        final int totalLen = 2 + r.length + 2 + s.length;
        final byte[] der = new byte[2 + totalLen];
        int i = 0;
        der[i++] = 0x30;
        der[i++] = (byte) totalLen;
        der[i++] = 0x02;
        der[i++] = (byte) r.length;
        System.arraycopy(r, 0, der, i, r.length);
        i += r.length;
        der[i++] = 0x02;
        der[i++] = (byte) s.length;
        System.arraycopy(s, 0, der, i, s.length);
        return der;
    }

}
