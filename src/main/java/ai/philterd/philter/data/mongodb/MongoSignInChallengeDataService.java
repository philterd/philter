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

import ai.philterd.philter.data.services.StoredSignInChallengeDataService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/** Stores sign-in challenges in MongoDB, which removes them once they expire. */
public class MongoSignInChallengeDataService extends StoredSignInChallengeDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoSignInChallengeDataService.class);

    private final MongoCollection<Document> collection;

    public MongoSignInChallengeDataService(final MongoClient mongoClient) {
        this.collection = mongoClient.getDatabase("philter").getCollection("sign_in_challenges");
        try {
            // Removes expired challenges; remove() also refuses them, since removal runs only periodically.
            collection.createIndex(Indexes.ascending("expires_at"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
            collection.createIndex(Indexes.ascending("token_hash"), new IndexOptions().unique(true));
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create the sign-in challenge indexes: {}", ex.getMessage());
        }
    }

    @Override
    protected void insert(final String tokenHash, final String userId, final Date expiresAt) {
        collection.insertOne(new Document("token_hash", tokenHash)
                .append("user_id", MongoIds.objectId(userId)).append("expires_at", expiresAt));
    }

    @Override
    protected String remove(final String tokenHash, final Date now) {
        final Document document = collection.findOneAndDelete(Filters.and(
                Filters.eq("token_hash", tokenHash), Filters.gt("expires_at", now)));
        return document == null ? null : MongoIds.hex(document.getObjectId("user_id"));
    }

}
