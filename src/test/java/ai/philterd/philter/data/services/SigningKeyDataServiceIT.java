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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Integration tests for {@link SigningKeyDataService} against a real (in-memory) MongoDB. These
 * cover keypair persistence, cross-instance reload, regeneration, and output format correctness —
 * behaviors the mock-based unit tests approximate but cannot fully exercise against real storage.
 */
class SigningKeyDataServiceIT extends AbstractMongoIT {

    @Test
    void firstStartGeneratesAndPersistsKeyToMongo() {
        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, publisher);

        assertNotNull(service.getPublicKey());
        assertNotNull(service.getPrivateKey());

        final long count = mongoClient.getDatabase("philter").getCollection("signing_keys").countDocuments();
        assertEquals(1, count, "exactly one keypair document must be persisted to MongoDB on first start");

        verify(publisher).auditEvent(isNull(), eq(AuditLogEvent.SIGNING_KEY_GENERATED), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void subsequentStartLoadsPersistedKeyWithoutGeneratingANew() {
        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);

        final SigningKeyDataService service1 = new SigningKeyDataService(mongoClient, publisher);
        final String fingerprint1 = service1.getPublicKeyFingerprint();

        // Simulate a restart — new instance, same underlying MongoDB
        final SigningKeyDataService service2 = new SigningKeyDataService(mongoClient, publisher);
        final String fingerprint2 = service2.getPublicKeyFingerprint();

        assertEquals(fingerprint1, fingerprint2,
                "second start must load the same keypair, not generate a new one");

        final long count = mongoClient.getDatabase("philter").getCollection("signing_keys").countDocuments();
        assertEquals(1, count, "MongoDB must still contain exactly one keypair document after two starts");

        // Audit event fired only once (first start), not on reload
        verify(publisher, org.mockito.Mockito.times(1))
                .auditEvent(isNull(), eq(AuditLogEvent.SIGNING_KEY_GENERATED), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void regenerateProducesNewKeypairAndUpdatesMongoDocument() {
        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, publisher);
        final String fingerprintBefore = service.getPublicKeyFingerprint();

        final ObjectId actingUser = new ObjectId();
        service.regenerate(actingUser);

        final String fingerprintAfter = service.getPublicKeyFingerprint();
        assertNotEquals(fingerprintBefore, fingerprintAfter,
                "fingerprint must change after regeneration");

        final long count = mongoClient.getDatabase("philter").getCollection("signing_keys").countDocuments();
        assertEquals(1, count, "MongoDB must contain exactly one keypair document after regeneration");

        verify(publisher).auditEvent(isNull(), eq(AuditLogEvent.SIGNING_KEY_REGENERATED), eq(actingUser), isNull(), isNull(), isNull());
    }

    @Test
    void newInstanceAfterRegenerationLoadsRegeneratedKey() {
        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, publisher);
        service.regenerate(new ObjectId());
        final String fingerprintAfterRegenerate = service.getPublicKeyFingerprint();

        // New instance (simulating restart after regeneration) must load the regenerated key
        final SigningKeyDataService service2 = new SigningKeyDataService(mongoClient, publisher);
        assertEquals(fingerprintAfterRegenerate, service2.getPublicKeyFingerprint(),
                "new instance started after regeneration must load the regenerated key from MongoDB");
    }

    @Test
    void publicKeyJwkContainsRequiredRfc7518Fields() {
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, mock(AuditEventPublisher.class));
        final String jwk = service.getPublicKeyJwk();

        assertTrue(jwk.contains("\"kty\":\"EC\""), "JWK must have kty=EC");
        assertTrue(jwk.contains("\"crv\":\"P-256\""), "JWK must have crv=P-256 per RFC 7518");
        assertTrue(jwk.contains("\"x\":"), "JWK must contain x coordinate");
        assertTrue(jwk.contains("\"y\":"), "JWK must contain y coordinate");
    }

    @Test
    void publicKeyPemHasExpectedBoundaries() {
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, mock(AuditEventPublisher.class));
        final String pem = service.getPublicKeyPem();

        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"), "PEM must start with BEGIN PUBLIC KEY header");
        assertTrue(pem.contains("-----END PUBLIC KEY-----"), "PEM must contain END PUBLIC KEY footer");
    }

    @Test
    void publicKeyFingerprintIsColonSeparatedSha256Hex() {
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, mock(AuditEventPublisher.class));
        final String fingerprint = service.getPublicKeyFingerprint();

        // SHA-256 = 32 bytes = "xx:xx:...:xx" = 32*2 hex chars + 31 colons = 95 characters
        assertEquals(95, fingerprint.length(), "SHA-256 fingerprint must be 95 characters");
        assertTrue(fingerprint.matches("[0-9a-f]{2}(:[0-9a-f]{2}){31}"),
                "fingerprint must be colon-separated lowercase hex pairs");
    }

}
