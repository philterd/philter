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

import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * Challenges issued at sign-in to a user enrolled in MFA: proof the password was right, exchanged with a
 * code for a session key. Each is short-lived and single-use, and only its hash is stored. Kept in the
 * database rather than in memory so any node can complete a sign-in another node began.
 */
public class SignInChallengeDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SignInChallengeDataService.class);

    /** How long a challenge can be used. */
    public static final int LIFETIME_SECONDS = 300;

    /** A challenge as issued: the token, returned once, and when it expires. */
    public record Challenge(String token, Date expiresAt) {
    }

    private final MongoCollection<Document> collection;
    private final SecureRandom secureRandom = new SecureRandom();

    public SignInChallengeDataService(final MongoClient mongoClient) {
        this.collection = mongoClient.getDatabase("philter").getCollection("sign_in_challenges");
        try {
            // Removes expired challenges; consume() also refuses them, since removal runs only periodically.
            collection.createIndex(Indexes.ascending("expires_at"), new IndexOptions().expireAfter(0L, TimeUnit.SECONDS));
            collection.createIndex(Indexes.ascending("token_hash"), new IndexOptions().unique(true));
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create the sign-in challenge indexes: {}", ex.getMessage());
        }
    }

    public Challenge create(final ObjectId userId) {
        final byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        final String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        final Date expiresAt = new Date(System.currentTimeMillis() + LIFETIME_SECONDS * 1000L);
        collection.insertOne(new Document("token_hash", EncryptionService.hashSha256(token))
                .append("user_id", userId).append("expires_at", expiresAt));
        return new Challenge(token, expiresAt);
    }

    /**
     * Uses up a challenge, whether or not the code that comes with it is right, so each one allows a
     * single attempt.
     *
     * @return the user it was issued to, or {@code null} if it is unknown, used, or expired.
     */
    public ObjectId consume(final String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        final Document document = collection.findOneAndDelete(Filters.and(
                Filters.eq("token_hash", EncryptionService.hashSha256(token)), Filters.gt("expires_at", new Date())));
        return document == null ? null : document.getObjectId("user_id");
    }

}
