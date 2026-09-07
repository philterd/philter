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
import ai.philterd.philter.data.entities.AbstractEncryptedEntity;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Both TTL indexes are built from a service constructor, so a failure there takes the context down.
 * The real trigger cannot be driven here (mongo-java-server accepts a changed
 * {@code expireAfterSeconds}), so these use one it does produce: a unique index over duplicates.
 */
class TtlIndexChangeIT extends AbstractMongoIT {

    /** Only to reach the protected helper the TTL indexes go through. */
    private static final class ProbeService extends AbstractEncryptedService<AbstractEncryptedEntity> {

        ProbeService(final MongoClient mongoClient, final EncryptionService encryptionService,
                     final AuditEventPublisher auditEventPublisher) {
            super(mongoClient, "ttl_guard_probe", encryptionService, auditEventPublisher);
        }

        void createIndexUnguarded(final Bson keys, final IndexOptions options) {
            collection.createIndex(keys, options);
        }

        void createIndexGuarded(final Bson keys, final IndexOptions options) {
            ensureIndex(keys, options);
        }
    }

    private ProbeService probeServiceOverDuplicateData() {
        final ProbeService service =
                new ProbeService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
        final var collection = mongoClient.getDatabase("philter").getCollection("ttl_guard_probe");
        collection.insertOne(new Document("k", "duplicate"));
        collection.insertOne(new Document("k", "duplicate"));
        return service;
    }

    @Test
    @DisplayName("Required index failures stop service initialization")
    void ensureIndexWithOptionsPropagatesFailure() {

        final ProbeService service = probeServiceOverDuplicateData();
        final Bson keys = Indexes.ascending("k");
        final IndexOptions unique = new IndexOptions().unique(true);

        // So the assertion below is not vacuous.
        assertThrows(Exception.class, () -> service.createIndexUnguarded(keys, unique));

        assertThrows(Exception.class, () -> service.createIndexGuarded(keys, unique));

    }

    @Test
    @DisplayName("Both TTL-indexed services reject incompatible retention")
    void bothTtlServicesRejectIncompatibleIndexes() {

        mongoClient.getDatabase("philter").getCollection("pending_documents").createIndex(
                Indexes.ascending("retention_at"), new IndexOptions().expireAfter(1L, TimeUnit.SECONDS));
        mongoClient.getDatabase("philter").getCollection("webhook_deliveries").createIndex(
                Indexes.ascending("completed_at"), new IndexOptions().expireAfter(1L, TimeUnit.SECONDS));

        assertThrows(Exception.class, () -> new PendingDocumentDataService(
                mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class)));
        assertThrows(Exception.class, () -> new WebhookDeliveryDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class)));

    }

}
