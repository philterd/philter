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

import ai.philterd.philter.data.entities.AbstractEntity;
import ai.philterd.philter.audit.AuditEventPublisher;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;

public class AbstractService<T extends AbstractEntity> {

    private static final String database = "philter";

    public static final int MAX_RESULTS_PER_PAGE = 25;

    protected final MongoCollection<Document> collection;
    protected final AuditEventPublisher auditEventPublisher;

    public AbstractService(final MongoClient mongoClient, final String collectionName, final AuditEventPublisher auditEventPublisher) {

        final MongoDatabase mongoDatabase = mongoClient.getDatabase(database);
        this.collection = mongoDatabase.getCollection(collectionName);
        this.auditEventPublisher = auditEventPublisher;

    }

    public ObjectId save(T entity) {
        return collection.insertOne(entity.toDocument()).getInsertedId().asObjectId().getValue();
    }

    public void update(final T entity) {

        collection.updateOne(
                Filters.and(
                        Filters.eq("_id", entity.getId()),
                        Filters.eq("user_id", entity.getUserId())
                ),
                new Document("$set", entity.toDocument())
        );

    }

}
