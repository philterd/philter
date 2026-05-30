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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * These are services for manipulating data entities.
 * @param <T>
 */
public class AbstractEncryptedService<T extends AbstractEncryptedEntity> {

    private static final Logger ABSTRACT_ENCRYPTED_SERVICE_LOGGER = LoggerFactory.getLogger(AbstractEncryptedService.class);

    private static final String database = "philter";

    protected final MongoCollection<Document> collection;
    protected final EncryptionService encryptionService;
    protected final AuditEventPublisher auditEventPublisher;

    public AbstractEncryptedService(final MongoClient mongoClient, final String collectionName, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {

        final MongoDatabase mongoDatabase = mongoClient.getDatabase(database);
        this.collection = mongoDatabase.getCollection(collectionName);

        this.encryptionService = encryptionService;
        this.auditEventPublisher = auditEventPublisher;

    }

    /**
     * Creates an index on this service's collection if it does not already exist. Index creation is
     * idempotent in MongoDB, so this is safe to call on every startup. A failure is logged but never
     * propagated, so it cannot prevent the application from starting.
     *
     * @param keys The index key specification (see {@code com.mongodb.client.model.Indexes}).
     */
    protected void ensureIndex(final Bson keys) {
        try {
            collection.createIndex(keys);
        } catch (final Exception ex) {
            ABSTRACT_ENCRYPTED_SERVICE_LOGGER.warn("Unable to create index {} on collection '{}': {}",
                    keys, collection.getNamespace().getCollectionName(), ex.getMessage());
        }
    }

    public ObjectId save(T entity) {
        return collection.insertOne(entity.toDocument(encryptionService)).getInsertedId().asObjectId().getValue();
    }

    public void update(final T entity) {

        collection.updateOne(
                Filters.eq("_id", entity.getId()),
                new Document("$set", entity.toDocument(encryptionService))
        );

    }

}
