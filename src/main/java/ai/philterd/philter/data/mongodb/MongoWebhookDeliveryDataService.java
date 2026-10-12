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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.DuplicateRecordException;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredWebhookDeliveryDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.MongoWriteException;
import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Stores webhook deliveries in MongoDB, which removes finished ones after their retention period. */
public class MongoWebhookDeliveryDataService extends StoredWebhookDeliveryDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoWebhookDeliveryDataService.class);

    private final EncryptionService encryptionService;
    private final MongoCollection<Document> collection;

    public MongoWebhookDeliveryDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        this.encryptionService = encryptionService;
        final MongoRecords<WebhookDeliveryEntity> records = new MongoRecords<>(mongoClient, "webhook_deliveries",
                entity -> WebhookDeliveryDocuments.toDocument(entity, encryptionService));
        this.collection = records.collection;

        records.ensureIndex(Indexes.ascending("status", "next_attempt_at"));
        records.ensureIndex(Indexes.ascending("status", "claim_expires_at"));
        // A user's deliveries are listed newest first.
        records.ensureIndex(Indexes.compoundIndex(Indexes.ascending("user_id"), Indexes.descending("created_at")));

        final long ttlSeconds = retentionSeconds();
        records.ensureIndex(Indexes.ascending("completed_at"), new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));
        RequiredSchema.rejectUnexpectedExpiry(collection, "completed_at");

        LOGGER.info("TTL index on webhook_deliveries.completed_at set to expire after {} seconds.", ttlSeconds);
    }

    @Override
    protected WebhookDeliveryEntity claim(final Date now, final String claimToken, final Date claimExpiresAt) {
        final Bson expired = Filters.and(Filters.eq("status", WebhookDeliveryEntity.STATUS_PROCESSING),
                Filters.lte("claim_expires_at", now));

        // A worker that repeatedly dies must not leave a delivery in an endless reclaim loop.
        collection.updateMany(Filters.and(expired, Filters.gte("attempts", MAX_ATTEMPTS)),
                Updates.combine(Updates.set("status", WebhookDeliveryEntity.STATUS_FAILED),
                        Updates.set("last_error", "Delivery claim expired after the final attempt."),
                        Updates.set("updated_at", now), Updates.set("completed_at", now), Updates.unset("next_attempt_at"),
                        Updates.unset("claim_token"), Updates.unset("claim_expires_at")));

        final Bson query = Filters.and(Filters.lt("attempts", MAX_ATTEMPTS), Filters.or(
                Filters.and(Filters.eq("status", WebhookDeliveryEntity.STATUS_PENDING),
                        Filters.lte("next_attempt_at", now)), expired));
        final Bson update = Updates.combine(
                Updates.set("status", WebhookDeliveryEntity.STATUS_PROCESSING),
                Updates.set("claim_token", claimToken),
                Updates.set("claim_expires_at", claimExpiresAt),
                Updates.set("updated_at", now), Updates.inc("attempts", 1));
        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending("next_attempt_at"))
                .returnDocument(ReturnDocument.AFTER);
        final Document claimed = collection.findOneAndUpdate(query, update, options);
        return claimed != null ? WebhookDeliveryDocuments.fromDocument(claimed, encryptionService) : null;
    }

    private static Bson ownedClaim(final String id, final String claimToken, final Date now) {
        return Filters.and(Filters.eq("_id", MongoIds.objectId(id)),
                Filters.eq("status", WebhookDeliveryEntity.STATUS_PROCESSING),
                Filters.eq("claim_token", claimToken), Filters.gt("claim_expires_at", now));
    }

    @Override
    protected boolean markDelivered(final String id, final String claimToken, final Date now) {
        return collection.updateOne(ownedClaim(id, claimToken, now), Updates.combine(
                Updates.set("status", WebhookDeliveryEntity.STATUS_DELIVERED),
                Updates.set("delivered_at", now), Updates.set("completed_at", now), Updates.set("updated_at", now),
                Updates.unset("next_attempt_at"), Updates.unset("claim_token"),
                Updates.unset("claim_expires_at"))).getModifiedCount() == 1;
    }

    @Override
    protected boolean endAttempt(final String id, final String claimToken, final int currentAttempts,
                                 final Date nextAttemptAt, final String errorMessage, final Date now) {
        final boolean exhausted = nextAttemptAt == null;
        return collection.updateOne(Filters.and(ownedClaim(id, claimToken, now),
                        Filters.eq("attempts", currentAttempts)), Updates.combine(
                Updates.set("status", exhausted ? WebhookDeliveryEntity.STATUS_FAILED : WebhookDeliveryEntity.STATUS_PENDING),
                Updates.set("last_error", errorMessage), Updates.set("updated_at", now),
                exhausted ? Updates.unset("next_attempt_at") : Updates.set("next_attempt_at", nextAttemptAt),
                exhausted ? Updates.set("completed_at", now) : Updates.unset("completed_at"),
                Updates.unset("claim_token"), Updates.unset("claim_expires_at"))).getModifiedCount() == 1;
    }

    @Override
    public List<WebhookDeliveryEntity> findByUserId(final String userId, final int offset, final int limit,
                                                    final boolean descending) {
        final List<WebhookDeliveryEntity> deliveries = new ArrayList<>();
        for (final Document document : collection.find(Filters.eq("user_id", MongoIds.objectId(userId)))
                .projection(Projections.exclude("secret", "secret_encrypted_key", "payload", "claim_token"))
                .sort(MongoListings.sort(new Listings.Sort("created_at", descending)))
                .skip(offset).limit(limit)) {
            deliveries.add(WebhookDeliveryDocuments.fromDocument(document, encryptionService));
        }
        return deliveries;
    }

    @Override
    public long countByUserId(final String userId) {
        return collection.countDocuments(Filters.eq("user_id", MongoIds.objectId(userId)));
    }

    @Override
    protected void insert(final WebhookDeliveryEntity delivery) {
        try {
            collection.withWriteConcern(WriteConcern.MAJORITY).insertOne(WebhookDeliveryDocuments.toDocument(delivery, encryptionService));
        } catch (final MongoWriteException ex) {
            throw MongoRecords.isDuplicate(ex) ? new DuplicateRecordException(ex) : ex;
        }
    }

    @Override
    protected boolean exists(final String id) {
        return collection.withReadPreference(ReadPreference.primary()).withReadConcern(ReadConcern.MAJORITY)
                .find(Filters.eq("_id", MongoIds.objectId(id))).first() != null;
    }

}
