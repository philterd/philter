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

import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.diffuse.StoredPiiCountAggregatePublisher;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Set;

/** Records PII count aggregates in MongoDB, one document per context and day. */
public class MongoPiiCountAggregatePublisher extends StoredPiiCountAggregatePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PiiCountAggregatePublisher.class);

    static final String COLLECTION = "pii_count_aggregates";
    private final MongoCollection<Document> collection;

    public MongoPiiCountAggregatePublisher(final MongoClient mongoClient, final AdminSettingsDataService adminSettingsDataService) {
        super(adminSettingsDataService);
        this.collection = mongoClient.getDatabase("philter").getCollection(COLLECTION);

        // One aggregate document per (context, daily bucket).
        try {
            collection.createIndex(Indexes.ascending("context", "bucket_start"), new IndexOptions().unique(true));
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create index on {}: {}", COLLECTION, ex.getMessage());
        }
    }

    @Override
    protected void increment(final String context, final Date bucketStart, final Set<String> piiTypes, final Date now) {

        final Document filter = new Document("context", context).append("bucket_start", bucketStart);

        // Increment each present type's count by exactly one (document-presence), plus the total.
        final Document increments = new Document("total_documents", 1);
        for (final String piiType : piiTypes) {
            increments.append("counts." + piiType, 1);
        }

        collection.updateOne(filter, new Document("$inc", increments).append("$set", new Document("updated_at", now)),
                new UpdateOptions().upsert(true));
    }

}
