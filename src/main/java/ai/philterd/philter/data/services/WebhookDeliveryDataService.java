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

import java.util.Date;
import java.util.List;

/**
 * The deliveries queued for each user's webhook, with their status, attempts, and last error.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>Each due delivery is claimed by at most one worker at a time, and attempts are counted atomically.</li>
 *   <li>Completed deliveries are removed after their retention period; pending ones never expire.</li>
 *   <li>Secrets are stored encrypted, and what is written is read back unchanged.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping deliveries, and order by
 *   creation time.</li>
 * </ul>
 */
public interface WebhookDeliveryDataService {

    int MAX_ATTEMPTS = 8;

    /** Atomically claims due work or takes over an expired attempt with a fresh fencing token. */
    WebhookDeliveryEntity claimNextDue(final Date now);

    /** Returns false when this attempt no longer owns a live claim. */
    boolean markDelivered(final String id, final String claimToken);

    /** Returns false for stale outcomes, which must not change another attempt's state. */
    boolean rescheduleOrFail(final String id, final String claimToken,
            final int currentAttempts, final String errorMessage);

    /**
     * A page of the user's deliveries, newest first, without their secrets or payloads, which a listing
     * has no use for.
     */
    List<WebhookDeliveryEntity> findByUserId(final String userId, final int offset, final int limit);

    /** As above, oldest first unless {@code descending}. */
    List<WebhookDeliveryEntity> findByUserId(final String userId, final int offset, final int limit,
            final boolean descending);

    /** How many deliveries the user has, for paging {@link #findByUserId}. */
    long countByUserId(final String userId);

    /** A terminal job has one stable event ID. Reconciliation may safely retry after uncertainty. */
    void enqueueOnce(final WebhookDeliveryEntity delivery);

}
