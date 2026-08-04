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
    void aKeyStoredInPlaintextByAnEarlierBuildIsEncryptedOnLoad() throws Exception {
        // Write a record in the pre-encryption shape: private key in the clear, no wrapped key.
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair legacy = kpg.generateKeyPair();

        mongoClient.getDatabase("philter").getCollection("signing_keys").insertOne(new Document()
                .append("key_id", "legacy-key")
                .append("private_key", new Binary(legacy.getPrivate().getEncoded()))
                .append("public_key", new Binary(legacy.getPublic().getEncoded()))
                .append("created_at", new Date())
                .append("active", true));

        final SigningKeyDataService service = newService();

        // The key still works, unchanged.
        assertArrayEquals(legacy.getPrivate().getEncoded(), service.getPrivateKey().getEncoded(),
                "an existing key must keep working after upgrade");

        // ...and it is no longer stored in the clear.
        final Document stored = rawKey();
        assertNotNull(stored.getString("private_key_encrypted_key"), "the key must be encrypted on load");
        assertFalse(Arrays.equals(legacy.getPrivate().getEncoded(), ((Binary) stored.get("private_key")).getData()),
                "the plaintext private key must not remain in the database");
    }

    @Test
    void aSupersededKeyStoredInPlaintextIsAlsoEncrypted() throws Exception {
        // A superseded key is retained so entries it signed stay verifiable, which also means it can
        // still forge signatures for those entries. Leaving it in the clear would defeat the point.
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair old = kpg.generateKeyPair();

        mongoClient.getDatabase("philter").getCollection("signing_keys").insertOne(new Document()
                .append("key_id", "old-key")
                .append("private_key", new Binary(old.getPrivate().getEncoded()))
                .append("public_key", new Binary(old.getPublic().getEncoded()))
                .append("created_at", new Date())
                .append("active", false)
                .append("superseded_at", new Date()));

        final SigningKeyDataService service = newService();

        final Document supersededDoc = mongoClient.getDatabase("philter").getCollection("signing_keys")
                .find(new Document("key_id", "old-key")).first();
        assertNotNull(supersededDoc.getString("private_key_encrypted_key"),
                "a superseded plaintext key must be encrypted too");
        assertFalse(Arrays.equals(old.getPrivate().getEncoded(),
                        ((Binary) supersededDoc.get("private_key")).getData()),
                "the superseded private key must not remain in the clear");

        // ...and it is still usable for verifying what it signed.
        assertNotNull(service.findPublicKeyById("old-key"));
        assertArrayEquals(old.getPublic().getEncoded(), service.findPublicKeyById("old-key").getEncoded());
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
