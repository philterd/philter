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

import ai.philterd.philter.data.entities.VectorEntity;
import ai.philterd.philter.services.vectors.StoredVectorServices;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;

/** Stores disambiguation vectors in MongoDB, one document per hash. */
public class MongoVectorServices extends StoredVectorServices {

    private final MongoCollection<Document> collection;

    public MongoVectorServices(final MongoClient mongoClient) {
        final MongoRecords<VectorEntity> records = new MongoRecords<>(mongoClient, "vectors", VectorDocuments::toDocument);
        this.collection = records.collection;
        // Vector representation lookups query (user_id, context, filter_type); size/eviction
        // checks query (user_id, context).
        records.ensureIndex(Indexes.ascending("user_id", "context", "filter_type"));
        records.ensureIndex(Indexes.ascending("user_id", "context"));
    }

    private static Bson inContext(final String userId, final String context) {
        return Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("context", context));
    }

    @Override
    protected void insert(final VectorEntity vector) {
        collection.insertOne(VectorDocuments.toDocument(vector));
    }

    @Override
    protected long count(final String userId, final String context) {
        return collection.countDocuments(inContext(userId, context));
    }

    @Override
    protected void deleteOldest(final String userId, final String context) {
        final Document victim = collection.find(inContext(userId, context)).sort(Sorts.ascending("_id")).first();
        if (victim != null) {
            collection.deleteOne(Filters.eq("_id", victim.getObjectId("_id")));
        }
    }

    @Override
    protected List<Double> findHashes(final String userId, final String context, final String filterType) {
        final List<Double> hashes = new ArrayList<>();
        for (final Document document : collection.find(Filters.and(inContext(userId, context), Filters.eq("filter_type", filterType)))
                .projection(Projections.include("hash"))) {
            hashes.add(document.getDouble("hash"));
        }
        return hashes;
    }

    @Override
    public void deleteByContext(final String userId, final String context) {
        collection.deleteMany(inContext(userId, context));
    }

}
