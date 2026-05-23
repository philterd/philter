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
import java.util.concurrent.TimeUnit;

public class WebhookDeliveryDataService extends AbstractService<WebhookDeliveryEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryDataService.class);

    public static final int MAX_ATTEMPTS = 8;

    private static final long DEFAULT_TTL_SECONDS = 30L * 24L * 60L * 60L;
    private static final long[] BACKOFF_SECONDS = {30, 60, 300, 900, 1800, 3600, 7200, 14400};

    public WebhookDeliveryDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "webhook_deliveries", auditEventPublisher);

        collection.createIndex(Indexes.ascending("status", "next_attempt_at"));

        final long ttlSeconds = Long.parseLong(
                System.getenv().getOrDefault("WEBHOOK_DELIVERIES_TTL_SECONDS", String.valueOf(DEFAULT_TTL_SECONDS)));

        collection.createIndex(
                Indexes.ascending("delivered_at"),
                new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));

        LOGGER.info("TTL index on webhook_deliveries.delivered_at set to expire after {} seconds.", ttlSeconds);
    }

    public WebhookDeliveryEntity claimNextDue(final Date now) {

        final Bson query = Filters.and(
                Filters.eq("status", WebhookDeliveryEntity.STATUS_PENDING),
                Filters.lte("next_attempt_at", now)
        );

        final Bson update = Updates.combine(
                Updates.set("updated_at", now),
                Updates.inc("attempts", 1)
        );

        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending("next_attempt_at"))
                .returnDocument(ReturnDocument.AFTER);

        final Document claimed = collection.findOneAndUpdate(query, update, options);
        return claimed != null ? WebhookDeliveryEntity.fromDocument(claimed) : null;

    }

    public void markDelivered(final ObjectId id) {

        final Date now = new Date();
        collection.updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.set("status", WebhookDeliveryEntity.STATUS_DELIVERED),
                        Updates.set("delivered_at", now),
                        Updates.set("updated_at", now),
                        Updates.unset("next_attempt_at")
                )
        );

    }

    public void rescheduleOrFail(final ObjectId id, final int currentAttempts, final String errorMessage) {

        final Date now = new Date();

        if (currentAttempts >= MAX_ATTEMPTS) {

            collection.updateOne(
                    Filters.eq("_id", id),
                    Updates.combine(
                            Updates.set("status", WebhookDeliveryEntity.STATUS_FAILED),
                            Updates.set("last_error", errorMessage),
                            Updates.set("updated_at", now),
                            Updates.unset("next_attempt_at")
                    )
            );
            return;
        }

        final long backoffSeconds = BACKOFF_SECONDS[Math.min(currentAttempts - 1, BACKOFF_SECONDS.length - 1)];
        final Date nextAttempt = new Date(now.getTime() + backoffSeconds * 1000L);

        collection.updateOne(
                Filters.eq("_id", id),
                Updates.combine(
                        Updates.set("last_error", errorMessage),
                        Updates.set("next_attempt_at", nextAttempt),
                        Updates.set("updated_at", now)
                )
        );

    }

}
