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
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SigningKeyDataServiceTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> mongoCollection;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private FindIterable<Document> findIterable;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("signing_keys")).thenReturn(mongoCollection);
        when(mongoCollection.find()).thenReturn(findIterable);
        when(mongoCollection.insertOne(any())).thenReturn(mock(InsertOneResult.class));
        when(mongoCollection.deleteMany(any())).thenReturn(mock(DeleteResult.class));
    }

    @Test
    void autoGeneratesKeyAndEmitsAuditEventWhenMongoIsEmpty() {
        when(findIterable.first()).thenReturn(null);

        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, auditEventPublisher);

        assertNotNull(service.getPrivateKey(), "private key must be populated after auto-generation");
        assertNotNull(service.getPublicKey(), "public key must be populated after auto-generation");
        verify(mongoCollection).insertOne(any());
        verify(auditEventPublisher).auditEvent(
                isNull(), eq(AuditLogEvent.SIGNING_KEY_GENERATED), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void loadsExistingKeyFromMongoOnStartupWithoutAuditEvent() throws Exception {
        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair existing = kpg.generateKeyPair();

        final Document doc = new Document("_id", new ObjectId())
                .append("private_key", new Binary(existing.getPrivate().getEncoded()))
                .append("public_key", new Binary(existing.getPublic().getEncoded()))
                .append("created_at", new Date());

        when(findIterable.first()).thenReturn(doc);

        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, auditEventPublisher);

        assertArrayEquals(existing.getPublic().getEncoded(), service.getPublicKey().getEncoded(),
                "public key loaded from MongoDB must match the persisted key — same keypair across restarts");
        verify(mongoCollection, never()).insertOne(any());
        verify(auditEventPublisher, never()).auditEvent(any(), any(AuditLogEvent.class), any(), any(), any(), any());
    }

    @Test
    void regenerateReplacesKeyAndEmitsAuditEventWithCorrectActingUser() {
        when(findIterable.first()).thenReturn(null);
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, auditEventPublisher);

        final ObjectId actingUserId = new ObjectId();
        service.regenerate(actingUserId);

        verify(mongoCollection).deleteMany(any());
        verify(auditEventPublisher).auditEvent(
                isNull(), eq(AuditLogEvent.SIGNING_KEY_REGENERATED), eq(actingUserId), isNull(), isNull(), isNull());
    }

    @Test
    void loadFromPemFileDerivesPublicKeyMatchingOriginal() throws Exception {
        when(findIterable.first()).thenReturn(null);
        final SigningKeyDataService service = new SigningKeyDataService(mongoClient, auditEventPublisher);

        final KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        final KeyPair original = kpg.generateKeyPair();

        final String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(original.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        final Path pemFile = tempDir.resolve("test-signing-key.pem");
        Files.writeString(pemFile, pem);

        final KeyPair loaded = service.loadFromPemFile(pemFile.toString());

        assertArrayEquals(original.getPublic().getEncoded(), loaded.getPublic().getEncoded(),
                "public key derived from PEM file must match the original — PHILTER_SIGNING_KEY_PATH PEM loading is correct");
    }

}
