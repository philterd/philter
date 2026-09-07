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
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SigningKeyDataServiceTest extends AbstractMongoIT {
    @TempDir Path tempDir;

    private SigningKeyDataService service() {
        return new SigningKeyDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
    }

    @Test
    void startupPublishesOneKeyAndAuditsGeneration() {
        final var audit = mock(AuditEventPublisher.class);
        final var first = new SigningKeyDataService(mongoClient, new TestEncryptionService(), audit);
        assertEquals(first.getActiveKeyId(), service().getActiveKeyId());
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("signing_key_state").countDocuments());
        verify(audit).auditEvent(null, AuditLogEvent.SIGNING_KEY_GENERATED, null, null, null, null);
    }

    @Test
    void rotationRetainsOldKeyAndAuditsActor() {
        final var audit = mock(AuditEventPublisher.class);
        final var first = new SigningKeyDataService(mongoClient, new TestEncryptionService(), audit);
        final String old = first.getActiveKeyId();
        final var second = service();
        final var actor = new ObjectId();
        first.regenerate(actor);
        assertNotEquals(old, first.getActiveKeyId());
        assertEquals(first.getActiveKeyId(), second.getActiveKeyId());
        assertNotNull(second.findPublicKeyById(old));
        verify(audit).auditEvent(null, AuditLogEvent.SIGNING_KEY_REGENERATED, actor, null, null, null);
    }

    @Test
    void failedCandidatePersistenceLeavesSharedPointerUnchanged() {
        final var encryption = spy(new TestEncryptionService());
        final var first = new SigningKeyDataService(mongoClient, encryption, mock(AuditEventPublisher.class));
        final String before = first.getActiveKeyId();
        doThrow(new IllegalStateException("encryption unavailable")).when(encryption).encryptBytes(any(), anyString());
        assertThrows(IllegalStateException.class, () -> first.regenerate(null));
        assertEquals(before, service().getActiveKeyId());
        assertEquals(before, first.getActiveKeyId());
    }

    @Test
    void missingPointerDoesNotFallBackToCachedSigningKey() {
        final var first = service();
        mongoClient.getDatabase("philter").getCollection("signing_key_state").deleteMany(new Document());
        assertThrows(IllegalStateException.class, first::currentSigningKey);
        assertThrows(IllegalStateException.class, first::getPublicKeyInfo);
        assertThrows(IllegalStateException.class, () -> first.regenerate(null));
    }

    @Test
    void pointerToMissingKeyDoesNotFallBackToCachedSigningKey() {
        final var first = service();
        mongoClient.getDatabase("philter").getCollection("signing_key_state")
                .updateOne(new Document("_id", "active"), new Document("$set", new Document("key_id", "missing")));
        assertThrows(IllegalStateException.class, first::currentSigningKey);
    }

    @Test
    void stableIdAndPublicResponseDescribeSameKey() {
        final var first = service();
        final var info = first.getPublicKeyInfo();
        assertEquals(SigningKeyDataService.keyIdFor(first.getPublicKey()), info.keyId());
        assertTrue(info.jwk().contains(info.keyId()));
        assertEquals(first.getPublicKeyPem(), info.pem());
        assertEquals(first.getPublicKeyFingerprint(), info.fingerprint());
    }

    @Test
    void pemModeIsExplicitAndRetainsOnlyPublicKey() throws Exception {
        final var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        final var pair = generator.generateKeyPair();
        final var path = tempDir.resolve("key.pem");
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        final var first = new SigningKeyDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), path.toString());
        final var second = new SigningKeyDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), path.toString());
        assertTrue(first.isExternallyManaged());
        assertArrayEquals(pair.getPublic().getEncoded(), first.getPublicKey().getEncoded());
        assertEquals(first.getActiveKeyId(), second.getActiveKeyId());
        assertThrows(IllegalStateException.class, () -> first.regenerate(null));
        final var stored = mongoClient.getDatabase("philter").getCollection("signing_keys").find().first();
        assertFalse(stored.containsKey("private_key"));
        assertEquals(0, mongoClient.getDatabase("philter").getCollection("signing_key_state").countDocuments());
    }
    @Test
    void failedPointerPublicationLeavesEveryInstanceOnPreviousKey() {
        final var first = service();
        final String before = first.getActiveKeyId();
        final var realDb = mongoClient.getDatabase("philter");
        final var state = spy(realDb.getCollection("signing_key_state"));
        doReturn(state).when(state).withReadPreference(any());
        doReturn(state).when(state).withWriteConcern(any());
        final var client = mock(com.mongodb.client.MongoClient.class);
        final var db = mock(com.mongodb.client.MongoDatabase.class);
        when(client.getDatabase("philter")).thenReturn(db);
        when(db.getCollection("signing_keys")).thenReturn(realDb.getCollection("signing_keys"));
        when(db.getCollection("signing_key_state")).thenReturn(state);
        final var rotating = new SigningKeyDataService(client, new TestEncryptionService(), mock(AuditEventPublisher.class));
        doThrow(new IllegalStateException("publication failed")).when(state)
                .updateOne(any(org.bson.conversions.Bson.class), any(org.bson.conversions.Bson.class));
        assertThrows(IllegalStateException.class, () -> rotating.regenerate(null));
        assertEquals(before, first.getActiveKeyId());
        assertEquals(before, rotating.currentSigningKey().keyId());
        assertEquals(before, service().getActiveKeyId());
    }

}
