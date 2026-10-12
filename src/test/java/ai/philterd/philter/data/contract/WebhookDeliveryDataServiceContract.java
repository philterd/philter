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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link WebhookDeliveryDataService}. */
public interface WebhookDeliveryDataServiceContract extends StorageContract {

    private WebhookDeliveryEntity delivery(final String owner, final String documentId, final Date createdAt) {
        return delivery(RecordIds.newId(), owner, documentId, createdAt);
    }

    /** A delivery whose identity is {@code id}: enqueuing the same identity again is the same delivery. */
    private WebhookDeliveryEntity delivery(final String id, final String owner, final String documentId,
            final Date createdAt) {
        final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setId(id);
        delivery.setUserId(owner);
        delivery.setDocumentId(documentId);
        delivery.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
        delivery.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
        delivery.setUrl("https://example.com/hook");
        delivery.setSecret("a secret, \"quoted\", Ünïcödé");
        delivery.setPayload("{}");
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(createdAt);
        delivery.setCreatedAt(createdAt);
        delivery.setUpdatedAt(createdAt);
        return delivery;
    }

    @Test
    default void webhookDeliveryConcurrentWorkersClaimADeliveryOnceAndCountTheAttempt() throws Exception {
        final String owner = RecordIds.newId();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", new Date()));
        final Date now = new Date();
        final List<WebhookDeliveryEntity> claims = StorageContract.concurrently(8,
                () -> storage().anotherInstance().webhookDeliveries().claimNextDue(now));
        final List<WebhookDeliveryEntity> won = claims.stream().filter(Objects::nonNull).toList();
        assertEquals(1, won.size());
        assertEquals(1, won.get(0).getAttempts());
        assertEquals("a secret, \"quoted\", Ünïcödé", won.get(0).getSecret());
    }

    /** The owner's one delivery, as stored. */
    private WebhookDeliveryEntity stored(final String owner) {
        final List<WebhookDeliveryEntity> deliveries = storage().anotherInstance().webhookDeliveries()
                .findByUserId(owner, 0, 10, false);
        assertEquals(1, deliveries.size());
        return deliveries.get(0);
    }

    @Test
    default void webhookDeliveryOnlyDueDeliveriesAreClaimedOldestFirst() {
        final String owner = RecordIds.newId();
        final Date now = new Date();
        assertNull(storage().webhookDeliveries().claimNextDue(now));
        final WebhookDeliveryEntity future = delivery(owner, "future", new Date(now.getTime() + 60_000));
        storage().webhookDeliveries().enqueueOnce(future);
        assertNull(storage().webhookDeliveries().claimNextDue(now), "a delivery not yet due is not claimed");
        final WebhookDeliveryEntity later = delivery(owner, "later", new Date(now.getTime() - 1000));
        final WebhookDeliveryEntity earlier = delivery(owner, "earlier", new Date(now.getTime() - 5000));
        storage().webhookDeliveries().enqueueOnce(later);
        storage().webhookDeliveries().enqueueOnce(earlier);

        final WebhookDeliveryEntity first = storage().webhookDeliveries().claimNextDue(now);
        final WebhookDeliveryEntity second = storage().webhookDeliveries().claimNextDue(now);
        assertEquals(earlier.getId(), first.getId());
        assertEquals(later.getId(), second.getId(), "a live claim does not hold up other due deliveries");
        assertEquals(WebhookDeliveryEntity.STATUS_PROCESSING, first.getStatus());
        assertTrue(first.getClaimExpiresAt().after(now));
        assertNull(storage().webhookDeliveries().claimNextDue(now));
    }

    @Test
    default void webhookDeliveryAStaleClaimCannotFinishTheDeliveryOnceAnotherWorkerHasIt() {
        final String owner = RecordIds.newId();
        final Date now = new Date();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", now));
        final WebhookDeliveryEntity old = storage().webhookDeliveries().claimNextDue(now);
        assertNull(storage().webhookDeliveries().claimNextDue(now), "a live claim is not claimed again");

        // The first worker's lease runs out and another worker claims the delivery.
        final WebhookDeliveryEntity current = storage().anotherInstance().webhookDeliveries()
                .claimNextDue(old.getClaimExpiresAt());
        assertEquals(old.getId(), current.getId());
        assertEquals(2, current.getAttempts());
        assertNotEquals(old.getClaimToken(), current.getClaimToken());

        assertFalse(storage().webhookDeliveries().markDelivered(old.getId(), old.getClaimToken()));
        assertFalse(storage().webhookDeliveries().rescheduleOrFail(old.getId(), old.getClaimToken(), old.getAttempts(), "old"));
        assertTrue(storage().webhookDeliveries().markDelivered(current.getId(), current.getClaimToken()));
        assertFalse(storage().webhookDeliveries().markDelivered(current.getId(), current.getClaimToken()), "delivered once");
        assertFalse(storage().webhookDeliveries().rescheduleOrFail(old.getId(), old.getClaimToken(), old.getAttempts(), "late"));
        assertFalse(storage().webhookDeliveries().rescheduleOrFail(current.getId(), current.getClaimToken(),
                current.getAttempts(), "duplicate"));
        final WebhookDeliveryEntity delivered = stored(owner);
        assertEquals(WebhookDeliveryEntity.STATUS_DELIVERED, delivered.getStatus());
        assertNull(delivered.getClaimToken());
        assertNotNull(delivered.getDeliveredAt());
        assertNull(storage().webhookDeliveries().claimNextDue(new Date(System.currentTimeMillis() + 86_400_000L)));
    }

    @Test
    default void webhookDeliveryAWorkerWhoseLeaseRanOutCannotFinishEvenBeforeAnotherClaims() {
        final String owner = RecordIds.newId();
        final Date longAgo = new Date(System.currentTimeMillis() - 86_400_000L);
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", longAgo));
        // Claimed as of a day ago, so its lease is already over now.
        final WebhookDeliveryEntity claim = storage().webhookDeliveries().claimNextDue(longAgo);
        assertFalse(storage().webhookDeliveries().markDelivered(claim.getId(), claim.getClaimToken()));
        assertFalse(storage().webhookDeliveries().rescheduleOrFail(claim.getId(), claim.getClaimToken(), 1, "late"));
        assertEquals(WebhookDeliveryEntity.STATUS_PROCESSING, stored(owner).getStatus());
    }

    @Test
    default void webhookDeliveryAFailedAttemptIsRetriedLaterUnderANewClaim() {
        final String owner = RecordIds.newId();
        final Date now = new Date();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", now));
        final WebhookDeliveryEntity first = storage().webhookDeliveries().claimNextDue(now);
        assertFalse(storage().webhookDeliveries().rescheduleOrFail(first.getId(), first.getClaimToken(), 2, "wrong attempt"));
        final long before = System.currentTimeMillis();
        assertTrue(storage().webhookDeliveries().rescheduleOrFail(first.getId(), first.getClaimToken(), 1, "transient"));

        final WebhookDeliveryEntity pending = stored(owner);
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, pending.getStatus());
        assertEquals("transient", pending.getLastError());
        assertNull(pending.getClaimToken());
        assertTrue(pending.getNextAttemptAt().getTime() >= before + 30_000, "retried after a backoff");
        assertNull(storage().webhookDeliveries().claimNextDue(now));

        final WebhookDeliveryEntity retry = storage().anotherInstance().webhookDeliveries().claimNextDue(pending.getNextAttemptAt());
        assertEquals(2, retry.getAttempts());
        assertNotEquals(first.getClaimToken(), retry.getClaimToken());
        assertEquals("a secret, \"quoted\", Ünïcödé", retry.getSecret(), "a retry signs with the original secret");
        assertFalse(storage().webhookDeliveries().markDelivered(first.getId(), first.getClaimToken()));
    }

    @Test
    default void webhookDeliveryTheFinalFailedAttemptIsTerminal() {
        final String owner = RecordIds.newId();
        final Date now = new Date();
        final WebhookDeliveryEntity almostSpent = delivery(owner, "doc", now);
        almostSpent.setAttempts(WebhookDeliveryDataService.MAX_ATTEMPTS - 1);
        storage().webhookDeliveries().enqueueOnce(almostSpent);
        final WebhookDeliveryEntity last = storage().webhookDeliveries().claimNextDue(now);
        assertEquals(WebhookDeliveryDataService.MAX_ATTEMPTS, last.getAttempts());
        assertTrue(storage().webhookDeliveries().rescheduleOrFail(last.getId(), last.getClaimToken(), last.getAttempts(), "exhausted"));
        assertFalse(storage().webhookDeliveries().markDelivered(last.getId(), last.getClaimToken()));

        final WebhookDeliveryEntity failed = stored(owner);
        assertEquals(WebhookDeliveryEntity.STATUS_FAILED, failed.getStatus());
        assertNull(failed.getClaimToken());
        assertNull(failed.getNextAttemptAt());
        assertNull(storage().webhookDeliveries().claimNextDue(new Date(System.currentTimeMillis() + 86_400_000L)));
    }

    @Test
    default void webhookDeliveryRepeatedlyAbandonedClaimsStopAtTheAttemptLimit() {
        final String owner = RecordIds.newId();
        Date now = new Date();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", now));
        for (int attempt = 1; attempt <= WebhookDeliveryDataService.MAX_ATTEMPTS; attempt++) {
            final WebhookDeliveryEntity claim = storage().webhookDeliveries().claimNextDue(now);
            assertNotNull(claim);
            assertEquals(attempt, claim.getAttempts());
            now = claim.getClaimExpiresAt();
        }
        assertNull(storage().webhookDeliveries().claimNextDue(now));
        final WebhookDeliveryEntity failed = stored(owner);
        assertEquals(WebhookDeliveryEntity.STATUS_FAILED, failed.getStatus());
        assertEquals(WebhookDeliveryDataService.MAX_ATTEMPTS, failed.getAttempts());
        assertNull(failed.getClaimToken());
    }

    @Test
    default void webhookDeliveryAMissingClaimTokenChangesNothing() {
        final String owner = RecordIds.newId();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", new Date()));
        final String id = stored(owner).getId();
        assertThrows(IllegalArgumentException.class, () -> storage().webhookDeliveries().markDelivered(id, null));
        assertThrows(IllegalArgumentException.class, () -> storage().webhookDeliveries().rescheduleOrFail(id, "", 1, "failure"));
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, stored(owner).getStatus());
    }

    @Test
    default void webhookDeliveryEnqueuingTheSameEventTwiceDeliversItOnce() throws Exception {
        final String owner = RecordIds.newId();
        final String id = RecordIds.newId();
        StorageContract.concurrently(4, () -> {
            storage().anotherInstance().webhookDeliveries().enqueueOnce(delivery(id, owner, "doc", new Date()));
            return null;
        });
        assertEquals(1, storage().webhookDeliveries().countByUserId(owner));
    }

    @Test
    default void webhookDeliveryListingsOrderByCreationTime() {
        final String owner = RecordIds.newId();
        final long start = System.currentTimeMillis() - 60_000;
        for (final int i : List.of(2, 0, 4, 1, 3)) {
            storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc-" + i, new Date(start + i * 1000L)));
        }
        assertEquals(5, storage().webhookDeliveries().countByUserId(owner));
        final List<String> ascending = new java.util.ArrayList<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().webhookDeliveries().findByUserId(owner, offset, 2, false)
                    .forEach(delivery -> ascending.add(delivery.getDocumentId()));
        }
        assertEquals(List.of("doc-0", "doc-1", "doc-2", "doc-3", "doc-4"), ascending);
        assertEquals("doc-4", storage().webhookDeliveries().findByUserId(owner, 0, 1, true).get(0).getDocumentId());
    }

}
