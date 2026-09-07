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
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.Binary;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The ES256 private key is what makes ledger signatures and response signatures mean anything. Stored
 * in the clear beside the ledger it protects, anyone with database access could read it, rewrite a
 * chain and re-sign it. These tests pin that a database dump alone no longer yields it, while the
 * public half stays readable so unauthenticated verification still works.
 */
class SigningKeyEncryptionIT extends AbstractMongoIT {

    private SigningKeyDataService newService() {
        return new SigningKeyDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
    }

    private Document rawKey() {
        return mongoClient.getDatabase("philter").getCollection("signing_keys").find().first();
    }

    @Test
    void theStoredPrivateKeyIsNotTheRealPrivateKey() {
        final SigningKeyDataService service = newService();
        final byte[] actualPrivate = service.getPrivateKey().getEncoded();

        final Document stored = rawKey();
        assertNotNull(stored);
        final byte[] storedPrivate = ((Binary) stored.get("private_key")).getData();

        assertFalse(Arrays.equals(actualPrivate, storedPrivate),
                "the private key must not be readable from the database");
        assertNotNull(stored.getString("private_key_encrypted_key"), "the wrapped data key must be stored");
    }

    @Test
    void thePublicKeyStaysReadableWithoutTheMasterKey() {
        final SigningKeyDataService service = newService();
        final byte[] actualPublic = service.getPublicKey().getEncoded();

        // GET /api/signing-key serves this unauthenticated so a third party can verify signatures.
        // It is not a secret and must not be encrypted.
        final byte[] storedPublic = ((Binary) rawKey().get("public_key")).getData();

        assertArrayEquals(actualPublic, storedPublic, "the public key must remain in the clear");
        assertNull(rawKey().getString("public_key_encrypted_key"));
    }

    @Test
    void theKeyRoundTripsAcrossRestarts() {
        final SigningKeyDataService first = newService();
        final byte[] privateBefore = first.getPrivateKey().getEncoded();
        final String keyId = first.getActiveKeyId();

        // A second instance reads the same persisted key, decrypting it.
        final SigningKeyDataService second = newService();

        assertArrayEquals(privateBefore, second.getPrivateKey().getEncoded(),
                "the same keypair must survive a restart");
        assertEquals(keyId, second.getActiveKeyId());
    }

    @Test
    void unprotectedPrivateKeyIsRejectedInsteadOfMigrated() {
        final var first = newService();
        mongoClient.getDatabase("philter").getCollection("signing_keys").updateOne(
                new Document("key_id", first.getActiveKeyId()),
                new Document("$unset", new Document("private_key_encrypted_key", "")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, this::newService);
    }

    @Test
    void asupersededKeyIsStillRetrievableAndItsPublicHalfReadable() {
        final SigningKeyDataService service = newService();
        final String originalKeyId = service.getActiveKeyId();
        final PublicKey originalPublic = service.getPublicKey();

        service.regenerate(null);

        // Retention from #638 must survive encryption: entries signed with the old key still verify.
        final PublicKey retained = service.findPublicKeyById(originalKeyId);
        assertNotNull(retained, "the superseded key must remain retrievable");
        assertArrayEquals(originalPublic.getEncoded(), retained.getEncoded());
        assertTrue(service.getPublicKeyPem(originalKeyId).startsWith("-----BEGIN PUBLIC KEY-----"));
    }

    private static void assertEquals(final String expected, final String actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

}
