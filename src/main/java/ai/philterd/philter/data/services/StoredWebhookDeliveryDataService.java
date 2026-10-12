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

import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.utils.EnvUtils;

import java.util.Date;
import java.util.UUID;

/**
 * The rules for webhook deliveries, the same in every storage implementation: how long a claim lasts, when a
 * failed attempt is retried, when a delivery gives up, and how long finished deliveries are kept. An
 * implementation stores the deliveries and makes the claims and transitions atomic.
 */
public abstract class StoredWebhookDeliveryDataService implements WebhookDeliveryDataService {

    private static final long DEFAULT_RETENTION_SECONDS = 30L * 24L * 60L * 60L;

    /** How long to wait before each retry, by the attempt that failed. */
    private static final long[] BACKOFF_SECONDS = {30, 60, 300, 900, 1800, 3600, 7200};

    private final long claimLeaseMillis;

    protected StoredWebhookDeliveryDataService() {
        final int leaseSeconds = EnvUtils.getInt("WEBHOOK_CLAIM_LEASE_SECONDS", 300);
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("WEBHOOK_CLAIM_LEASE_SECONDS must be positive.");
        }
        claimLeaseMillis = leaseSeconds * 1000L;
    }

    /** How long delivered and failed deliveries are kept before they are removed. */
    protected static long retentionSeconds() {
        return EnvUtils.getLong("WEBHOOK_DELIVERIES_TTL_SECONDS", DEFAULT_RETENTION_SECONDS);
    }

    /**
     * Fails every delivery whose claim expired after its last allowed attempt, then claims the pending delivery
     * due soonest, or takes over one whose claim expired: sets it processing with this token and lease, adds one
     * to its attempts, and returns it. Returns null when nothing is due. Concurrent calls never claim the same
     * delivery.
     */
    protected abstract WebhookDeliveryEntity claim(final Date now, final String claimToken, final Date claimExpiresAt);

    /** Marks a delivery delivered, only while this token holds a live claim on it. */
    protected abstract boolean markDelivered(final String id, final String claimToken, final Date now);

    /**
     * Ends an attempt, only while this token holds a live claim and the delivery is at {@code currentAttempts}:
     * pending again at {@code nextAttemptAt}, or failed when it is null.
     */
    protected abstract boolean endAttempt(final String id, final String claimToken, final int currentAttempts,
            final Date nextAttemptAt, final String errorMessage, final Date now);

    /**
     * Stores a new delivery.
     *
     * @throws DuplicateRecordException when a delivery with its id is already stored.
     */
    protected abstract void insert(final WebhookDeliveryEntity delivery);

    /** Whether a delivery with this id is stored. */
    protected abstract boolean exists(final String id);

    @Override
    public WebhookDeliveryEntity claimNextDue(final Date now) {
        return claim(now, UUID.randomUUID().toString(), new Date(now.getTime() + claimLeaseMillis));
    }

    @Override
    public boolean markDelivered(final String id, final String claimToken) {
        return markDelivered(id, requireToken(claimToken), new Date());
    }

    @Override
    public boolean rescheduleOrFail(final String id, final String claimToken,
                                    final int currentAttempts, final String errorMessage) {
        if (currentAttempts < 1 || currentAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Invalid webhook attempt count.");
        }
        final Date now = new Date();
        final Date nextAttemptAt = currentAttempts == MAX_ATTEMPTS ? null
                : new Date(now.getTime() + BACKOFF_SECONDS[currentAttempts - 1] * 1000L);
        return endAttempt(id, requireToken(claimToken), currentAttempts, nextAttemptAt, errorMessage, now);
    }

    @Override
    public void enqueueOnce(final WebhookDeliveryEntity delivery) {
        if (delivery.getId() == null) throw new IllegalArgumentException("Notification identity is required.");
        try {
            insert(delivery);
        } catch (final DuplicateRecordException duplicate) {
            if (!exists(delivery.getId())) throw duplicate;
        }
    }

    private static String requireToken(final String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("A webhook claim token is required.");
        }
        return claimToken;
    }

}
