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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.services.DuplicateRecordException;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

/** One kind of record's collection, and how its records are inserted, updated, and indexed. */
final class MongoRecords<T> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoRecords.class);

    final MongoCollection<Document> collection;
    private final Function<T, Document> toDocument;

    MongoRecords(final MongoClient mongoClient, final String collectionName, final Function<T, Document> toDocument) {
        this.collection = mongoClient.getDatabase("philter").getCollection(collectionName);
        this.toDocument = toDocument;
    }

    /**
     * Inserts a record and returns its id.
     *
     * @throws DuplicateRecordException when a unique index refuses it.
     */
    String insert(final T record) {
        try {
            return MongoIds.hex(collection.insertOne(toDocument.apply(record)).getInsertedId().asObjectId().getValue());
        } catch (final MongoWriteException ex) {
            throw isDuplicate(ex) ? new DuplicateRecordException(ex) : ex;
        } catch (final com.mongodb.MongoWriteConcernException ex) {
            throw ex.getCode() == 11000 ? new DuplicateRecordException(ex) : ex;
        }
    }

    /**
     * Sets every field of the record with the given id.
     *
     * @throws DuplicateRecordException when a unique index refuses it.
     */
    void update(final String id, final T record) {
        try {
            collection.updateOne(Filters.eq("_id", MongoIds.objectId(id)), new Document("$set", toDocument.apply(record)));
        } catch (final MongoWriteException ex) {
            throw isDuplicate(ex) ? new DuplicateRecordException(ex) : ex;
        }
    }

    static boolean isDuplicate(final MongoWriteException ex) {
        return ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
    }

    /** Creates an index if it does not exist. A failure is logged and does not stop startup. */
    void ensureIndex(final Bson keys) {
        try {
            collection.createIndex(keys);
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create index {} on collection '{}': {}",
                    keys, collection.getNamespace().getCollectionName(), ex.getMessage());
        }
    }

    /** Creates a required constraint or retention index; startup fails if it cannot be established. */
    void ensureIndex(final Bson keys, final IndexOptions options) {
        RequiredSchema.ensureIndex(collection, keys, options);
    }

}
