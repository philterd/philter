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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * End-to-end integration tests for {@link SigningService} against a real (in-memory) MongoDB.
 * These exercise the full sign → verify path using real ES256 keypairs persisted and loaded
 * via {@link SigningKeyDataService}, covering scenarios that unit tests mock away: key persistence
 * across simulated restarts, signature invalidation after key regeneration, and JWT payload
 * correctness with real cryptographic operations.
 */
class SigningServiceIT extends AbstractMongoIT {

    private SigningKeyDataService keyService;
    private SigningService signingService;

    @BeforeEach
    void setUp() {
        keyService = new SigningKeyDataService(mongoClient, mock(AuditEventPublisher.class));
        signingService = new SigningService(keyService, mock(AdminSettingsDataService.class));
    }

    @Test
    void signedJwtVerifiesWithPublicKeyFromSameInstance() throws Exception {
        final String documentId = UUID.randomUUID().toString();
        final String jwt = signingService.sign("The patient's name is REDACTED.", "default", 3, documentId);

        assertTrue(verifyJwt(jwt, keyService.getPublicKey()),
                "JWT signed by the service must verify with its own public key");
    }

    @Test
    void jwtPayloadContainsCorrectClaims() throws Exception {
        final String documentId = UUID.randomUUID().toString();
        final String body = "Redacted text.";
        final String jwt = signingService.sign(body, "hipaa-policy", 7, documentId);

        final String payloadJson = new String(
                Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("\"policyName\":\"hipaa-policy\""), "payload must contain policyName");
        assertTrue(payloadJson.contains("\"policyVersion\":7"), "payload must contain policyVersion");
        assertTrue(payloadJson.contains("\"documentId\":\"" + documentId + "\""), "payload must contain documentId");
        assertTrue(payloadJson.contains("\"bodyHash\":"), "payload must contain bodyHash");
        assertTrue(payloadJson.contains("\"iat\":"), "payload must contain iat");
    }

    @Test
    void signatureVerifiesAfterSimulatedRestart() throws Exception {
        // Sign before restart
        final String jwt = signingService.sign("Redacted.", "default", 1, UUID.randomUUID().toString());

        // Simulate restart: new service instance backed by the same MongoDB
        final SigningKeyDataService reloadedKeyService =
                new SigningKeyDataService(mongoClient, mock(AuditEventPublisher.class));

        assertTrue(verifyJwt(jwt, reloadedKeyService.getPublicKey()),
                "JWT signed before restart must verify with the reloaded public key — same key persists across restarts");
    }

    @Test
    void signatureDoesNotVerifyWithNewPublicKeyAfterRegeneration() throws Exception {
        final String jwt = signingService.sign("Redacted.", "default", 1, UUID.randomUUID().toString());

        keyService.regenerate(new ObjectId());

        assertFalse(verifyJwt(jwt, keyService.getPublicKey()),
                "JWT signed with the old key must not verify with the new public key after regeneration");
    }

    @Test
    void newSignatureVerifiesWithNewKeyAfterRegeneration() throws Exception {
        keyService.regenerate(new ObjectId());

        final String jwt = signingService.sign("Redacted.", "default", 1, UUID.randomUUID().toString());

        assertTrue(verifyJwt(jwt, keyService.getPublicKey()),
                "JWT signed after regeneration must verify with the new public key");
    }

    @Test
    void signatureDoesNotVerifyForTamperedBody() throws Exception {
        final String originalJwt = signingService.sign("Original redacted text.", "default", 1, UUID.randomUUID().toString());

        // Re-sign a different body — the original signature must not validate against its signing input
        final String tamperedJwt = signingService.sign("Tampered text.", "default", 1, UUID.randomUUID().toString());
        final String[] tamperedParts = tamperedJwt.split("\\.");
        final String originalSigPart = originalJwt.split("\\.")[2];

        // Swap: use tampered header.payload with original signature
        final String crossJwt = tamperedParts[0] + "." + tamperedParts[1] + "." + originalSigPart;

        assertFalse(verifyJwt(crossJwt, keyService.getPublicKey()),
                "original signature must not verify against a different body's signing input");
    }

    // --- helpers ---

    private static boolean verifyJwt(final String jwt, final PublicKey publicKey) throws Exception {
        final String[] parts = jwt.split("\\.");
        final String signingInput = parts[0] + "." + parts[1];
        final byte[] p1363Sig = Base64.getUrlDecoder().decode(parts[2]);
        final byte[] derSig = p1363ToDer(p1363Sig);

        final Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return verifier.verify(derSig);
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
