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
import ai.philterd.philter.data.entities.AbstractEntity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AbstractService<T extends AbstractEntity> {

    private static final Logger ABSTRACT_SERVICE_LOGGER = LoggerFactory.getLogger(AbstractService.class);

    private static final String database = "philter";

    public static final int MAX_RESULTS_PER_PAGE = 25;

    protected final MongoCollection<Document> collection;
    protected final AuditEventPublisher auditEventPublisher;

    public AbstractService(final MongoClient mongoClient, final String collectionName, final AuditEventPublisher auditEventPublisher) {

        final MongoDatabase mongoDatabase = mongoClient.getDatabase(database);
        this.collection = mongoDatabase.getCollection(collectionName);
        this.auditEventPublisher = auditEventPublisher;

    }

    /**
     * Creates an index on this service's collection if it does not already exist. Index creation is
     * idempotent in MongoDB, so this is safe to call on every startup. A failure (for example, a
     * unique index that conflicts with existing data) is logged but never propagated, so it cannot
     * prevent the application from starting.
     *
     * @param keys The index key specification (see {@code com.mongodb.client.model.Indexes}).
     */
    protected void ensureIndex(final Bson keys) {
        try {
            collection.createIndex(keys);
        } catch (final Exception ex) {
            ABSTRACT_SERVICE_LOGGER.warn("Unable to create index {} on collection '{}': {}",
                    keys, collection.getNamespace().getCollectionName(), ex.getMessage());
        }
    }

    /**
     * Creates an index with the given options (for example, a unique index) if it does not already
     * exist. As with {@link #ensureIndex(Bson)}, a failure is logged but never propagated, so it
     * cannot prevent the application from starting. Note that a unique index will fail to build if
     * the collection already contains documents that violate it.
     *
     * @param keys    The index key specification (see {@code com.mongodb.client.model.Indexes}).
     * @param options The index options (see {@code com.mongodb.client.model.IndexOptions}).
     */
    protected void ensureIndex(final Bson keys, final IndexOptions options) {
        try {
            collection.createIndex(keys, options);
        } catch (final Exception ex) {
            ABSTRACT_SERVICE_LOGGER.warn("Unable to create index {} on collection '{}': {}",
                    keys, collection.getNamespace().getCollectionName(), ex.getMessage());
        }
    }

    public ObjectId save(T entity) {
        return collection.insertOne(entity.toDocument()).getInsertedId().asObjectId().getValue();
    }

    public void update(final T entity) {

        collection.updateOne(
                Filters.eq("_id", entity.getId()),
                new Document("$set", entity.toDocument())
        );

    }

}
