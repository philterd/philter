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
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.utils.EnvUtils;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class WebhookDeliveryDataService extends AbstractEncryptedService<WebhookDeliveryEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryDataService.class);

    public static final int MAX_ATTEMPTS = 8;

    private final long claimLeaseMillis;

    private static final long DEFAULT_TTL_SECONDS = 30L * 24L * 60L * 60L;
    private static final long[] BACKOFF_SECONDS = {30, 60, 300, 900, 1800, 3600, 7200};

    public WebhookDeliveryDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "webhook_deliveries", encryptionService, auditEventPublisher);

        final int leaseSeconds = EnvUtils.getInt("WEBHOOK_CLAIM_LEASE_SECONDS", 300);
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("WEBHOOK_CLAIM_LEASE_SECONDS must be positive.");
        }
        claimLeaseMillis = leaseSeconds * 1000L;
        ensureIndex(Indexes.ascending("status", "next_attempt_at"));
        ensureIndex(Indexes.ascending("status", "claim_expires_at"));

        final long ttlSeconds = EnvUtils.getLong("WEBHOOK_DELIVERIES_TTL_SECONDS", DEFAULT_TTL_SECONDS);

        ensureIndex(
                Indexes.ascending("completed_at"),
                new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));

        RequiredSchema.rejectUnexpectedExpiry(collection, "completed_at");

        LOGGER.info("TTL index on webhook_deliveries.completed_at set to expire after {} seconds.", ttlSeconds);
    }

    /** Atomically claims due work or takes over an expired attempt with a fresh fencing token. */
    public WebhookDeliveryEntity claimNextDue(final Date now) {
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
                Updates.set("claim_token", UUID.randomUUID().toString()),
                Updates.set("claim_expires_at", new Date(now.getTime() + claimLeaseMillis)),
                Updates.set("updated_at", now), Updates.inc("attempts", 1));
        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending("next_attempt_at"))
                .returnDocument(ReturnDocument.AFTER);
        final Document claimed = collection.findOneAndUpdate(query, update, options);
        return claimed != null ? WebhookDeliveryEntity.fromDocument(claimed, encryptionService) : null;
    }

    private Bson ownedClaim(final ObjectId id, final String claimToken, final Date now) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("A webhook claim token is required.");
        }
        return Filters.and(Filters.eq("_id", id),
                Filters.eq("status", WebhookDeliveryEntity.STATUS_PROCESSING),
                Filters.eq("claim_token", claimToken), Filters.gt("claim_expires_at", now));
    }

    /** Returns false when this attempt no longer owns a live claim. */
    public boolean markDelivered(final ObjectId id, final String claimToken) {
        final Date now = new Date();
        return collection.updateOne(ownedClaim(id, claimToken, now), Updates.combine(
                Updates.set("status", WebhookDeliveryEntity.STATUS_DELIVERED),
                Updates.set("delivered_at", now), Updates.set("completed_at", now), Updates.set("updated_at", now),
                Updates.unset("next_attempt_at"), Updates.unset("claim_token"),
                Updates.unset("claim_expires_at"))).getModifiedCount() == 1;
    }

    /** Returns false for stale outcomes, which must not change another attempt's state. */
    public boolean rescheduleOrFail(final ObjectId id, final String claimToken,
                                    final int currentAttempts, final String errorMessage) {
        if (currentAttempts < 1 || currentAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid webhook attempt count.");
        }
        final Date now = new Date();
        final boolean exhausted = currentAttempts == MAX_ATTEMPTS;
        final Bson scheduling = exhausted ? Updates.unset("next_attempt_at")
                : Updates.set("next_attempt_at", new Date(now.getTime() + BACKOFF_SECONDS[currentAttempts - 1] * 1000L));
        return collection.updateOne(Filters.and(ownedClaim(id, claimToken, now),
                        Filters.eq("attempts", currentAttempts)), Updates.combine(
                Updates.set("status", exhausted ? WebhookDeliveryEntity.STATUS_FAILED : WebhookDeliveryEntity.STATUS_PENDING),
                Updates.set("last_error", errorMessage), Updates.set("updated_at", now), scheduling,
                exhausted ? Updates.set("completed_at", now) : Updates.unset("completed_at"),
                Updates.unset("claim_token"), Updates.unset("claim_expires_at"))).getModifiedCount() == 1;
    }

    /** A terminal job has one stable event ID. Reconciliation may safely retry after uncertainty. */
    public void enqueueOnce(final WebhookDeliveryEntity delivery) {
        if (delivery.getId() == null) throw new IllegalArgumentException("Notification identity is required.");
        try {
            collection.withWriteConcern(com.mongodb.WriteConcern.MAJORITY)
                    .insertOne(delivery.toDocument(encryptionService));
        } catch (com.mongodb.MongoWriteException duplicate) {
            if (duplicate.getError().getCode() != 11000
                    || collection.withReadPreference(com.mongodb.ReadPreference.primary())
                    .withReadConcern(com.mongodb.ReadConcern.MAJORITY)
                    .find(Filters.eq("_id", delivery.getId())).first() == null) throw duplicate;
        }
    }
}
