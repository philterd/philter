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
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.QueueCapacityException;
import ai.philterd.philter.utils.EnvUtils;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** The guarantees of {@link PendingDocumentDataService}. */
public interface PendingDocumentDataServiceContract extends StorageContract {

    private String submit(final String owner, final byte[] input) {
        final PendingDocumentEntity job = new PendingDocumentEntity();
        job.setUserId(owner);
        job.setDocumentId(RecordIds.newId());
        job.setStatus(PendingDocumentEntity.STATUS_PENDING);
        job.setSubmittedAt(new Date());
        job.setInput(input);
        storage().pendingDocuments().save(job);
        return job.getDocumentId();
    }

    /** A time after every lease taken now has run out. */
    private Date afterTheLease() {
        return new Date(System.currentTimeMillis() + 2 * storage().pendingDocuments().getClaimLeaseMillis());
    }

    @Test
    default void pendingDocumentConcurrentWorkersClaimEachDocumentOnce() throws Exception {
        final String owner = RecordIds.newId();
        for (int i = 0; i < 3; i++) {
            submit(owner, new byte[]{1});
        }
        final List<PendingDocumentEntity> claims = StorageContract.concurrently(8,
                () -> storage().anotherInstance().pendingDocuments().claimNextPending("worker"));
        final List<String> claimed = claims.stream().filter(Objects::nonNull).map(PendingDocumentEntity::getId).toList();
        assertEquals(3, claimed.size());
        assertEquals(3, Set.copyOf(claimed).size());
    }

    @Test
    default void pendingDocumentAnExpiredLeaseCanBeClaimedAgainAndTheOldClaimIsFenced() {
        final String owner = RecordIds.newId();
        submit(owner, new byte[]{1});
        final PendingDocumentEntity first = storage().pendingDocuments().claimNextPending("first");
        assertNull(storage().pendingDocuments().claimNextPending("second"));
        assertEquals(1, storage().anotherInstance().pendingDocuments().reclaimStuckJobs(afterTheLease(), 3));
        final PendingDocumentEntity second = storage().anotherInstance().pendingDocuments().claimNextPending("second");
        assertNotNull(second);
        assertEquals(first.getId(), second.getId());
        // The worker whose lease was taken over can no longer write.
        assertFalse(storage().pendingDocuments().beginPublication(first.getId(), first.getClaimToken()));
        assertFalse(storage().pendingDocuments().markComplete(first.getId(), owner, first.getClaimToken(), new byte[]{0}));
        assertTrue(storage().pendingDocuments().beginPublication(second.getId(), second.getClaimToken()));
        assertTrue(storage().pendingDocuments().markComplete(second.getId(), owner, second.getClaimToken(), new byte[]{9}));
        assertArrayEquals(new byte[]{9},
                storage().pendingDocuments().findOneByDocumentIdAndUserId(first.getDocumentId(), owner).getOutput());
    }

    @Test
    default void pendingDocumentFinishedDocumentsAreNeverClaimed() {
        final String owner = RecordIds.newId();
        submit(owner, new byte[]{1});
        submit(owner, new byte[]{2});
        final PendingDocumentEntity completed = storage().pendingDocuments().claimNextPending("worker");
        assertTrue(storage().pendingDocuments().beginPublication(completed.getId(), completed.getClaimToken()));
        assertTrue(storage().pendingDocuments().markComplete(completed.getId(), owner, completed.getClaimToken(), new byte[]{9}));
        final PendingDocumentEntity failed = storage().pendingDocuments().claimNextPending("worker");
        assertTrue(storage().pendingDocuments().markFailed(failed.getId(), failed.getClaimToken(), "failed"));
        assertEquals(0, storage().pendingDocuments().reclaimStuckJobs(afterTheLease(), 3));
        assertNull(storage().pendingDocuments().claimNextPending("worker"));
    }

    @Test
    default void pendingDocumentInputsAndOutputsReadBackUnchanged() {
        final String owner = RecordIds.newId();
        final byte[] input = new byte[256];
        for (int i = 0; i < input.length; i++) {
            input[i] = (byte) i;
        }
        final String documentId = submit(owner, input);
        final PendingDocumentDataService other = storage().anotherInstance().pendingDocuments();
        assertArrayEquals(input, other.findOneByDocumentIdAndUserId(documentId, owner).getInput());
        final PendingDocumentEntity claim = other.claimNextPending("worker");
        assertArrayEquals(input, claim.getInput());
        assertTrue(other.beginPublication(claim.getId(), claim.getClaimToken()));
        assertTrue(other.markComplete(claim.getId(), owner, claim.getClaimToken(), input));
        assertArrayEquals(input, storage().pendingDocuments().findOneByDocumentIdAndUserId(documentId, owner).getOutput());
    }

    @Test
    default void pendingDocumentListingsCountAndPageWithoutRepeats() {
        final String owner = RecordIds.newId();
        for (int i = 0; i < 5; i++) {
            submit(owner, new byte[]{1});
        }
        submit(RecordIds.newId(), new byte[]{1});
        assertEquals(5, storage().pendingDocuments().list(owner, null, new Listings.Sort("submitted_at", false), 0, 1).total());
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().pendingDocuments().list(owner, null, new Listings.Sort("submitted_at", false), offset, 2).items()
                    .forEach(job -> assertTrue(seen.add(job.getDocumentId())));
        }
        assertEquals(5, seen.size());
    }

    private PendingDocumentEntity job(final String owner, final int size) {
        final PendingDocumentEntity job = new PendingDocumentEntity();
        job.setUserId(owner);
        job.setDocumentId(RecordIds.newId());
        job.setStatus(PendingDocumentEntity.STATUS_PENDING);
        job.setSubmittedAt(new Date());
        job.setInput(new byte[size]);
        return job;
    }

    @Test
    default void pendingDocumentASupersededClaimCanNeverFinishTheDocument() {
        final AtomicLong time = new AtomicLong(System.currentTimeMillis());
        final PendingDocumentDataService first = storage().pendingDocumentsAt(time::get);
        final PendingDocumentDataService second = storage().anotherInstance().pendingDocumentsAt(time::get);
        final String owner = RecordIds.newId();
        first.save(job(owner, 1));
        final PendingDocumentEntity old = first.claimNextPending("same-worker");

        time.addAndGet(first.getClaimLeaseMillis());
        assertFalse(first.beginPublication(old.getId(), old.getClaimToken()), "the lease is over");
        assertFalse(first.renewClaim(old.getId(), old.getClaimToken()));
        assertFalse(first.markFailed(old.getId(), old.getClaimToken(), "expired"));
        assertEquals(1, second.reclaimStuckJobs(new Date(time.get()), 3));
        final PendingDocumentEntity current = second.claimNextPending("same-worker");
        assertNotEquals(old.getClaimToken(), current.getClaimToken(), "a new claim, even for the same worker id");

        assertFalse(first.beginPublication(old.getId(), old.getClaimToken()));
        assertFalse(first.markComplete(old.getId(), owner, old.getClaimToken(), new byte[]{0}));
        assertTrue(second.beginPublication(current.getId(), current.getClaimToken()));
        assertTrue(second.markComplete(current.getId(), owner, current.getClaimToken(), new byte[]{9}));
        assertFalse(first.markFailed(old.getId(), old.getClaimToken(), "late failure"));
        assertFalse(second.markFailed(current.getId(), current.getClaimToken(), "duplicate"));
        assertFalse(second.markComplete(current.getId(), owner, current.getClaimToken(), new byte[]{0}));
        assertArrayEquals(new byte[]{9}, first.findOneByDocumentIdAndUserId(old.getDocumentId(), owner).getOutput());
    }

    @Test
    default void pendingDocumentRenewingAClaimKeepsASlowDocumentOwned() {
        final AtomicLong time = new AtomicLong(System.currentTimeMillis());
        final PendingDocumentDataService worker = storage().pendingDocumentsAt(time::get);
        final PendingDocumentDataService other = storage().anotherInstance().pendingDocumentsAt(time::get);
        final String owner = RecordIds.newId();
        worker.save(job(owner, 1));
        final PendingDocumentEntity claim = worker.claimNextPending("worker");
        final long step = worker.getClaimLeaseMillis() / 2;
        for (int i = 0; i < 10; i++) {
            time.addAndGet(step);
            assertTrue(worker.renewClaim(claim.getId(), claim.getClaimToken()));
            assertEquals(0, other.reclaimStuckJobs(new Date(time.get()), 3), "a renewed claim is not stuck");
        }
        assertTrue(worker.beginPublication(claim.getId(), claim.getClaimToken()));
        assertTrue(worker.markComplete(claim.getId(), owner, claim.getClaimToken(), new byte[]{9}));
    }

    @Test
    default void pendingDocumentAPublishingDocumentIsNeverReclaimedOrFailedByAnotherWorker() {
        final AtomicLong time = new AtomicLong(System.currentTimeMillis());
        final PendingDocumentDataService worker = storage().pendingDocumentsAt(time::get);
        final PendingDocumentDataService other = storage().anotherInstance().pendingDocumentsAt(time::get);
        final String owner = RecordIds.newId();
        worker.save(job(owner, 1));
        final PendingDocumentEntity claim = worker.claimNextPending("worker");
        assertFalse(worker.markComplete(claim.getId(), owner, claim.getClaimToken(), new byte[]{9}), "publication comes first");
        assertTrue(worker.beginPublication(claim.getId(), claim.getClaimToken()));
        assertFalse(worker.beginPublication(claim.getId(), claim.getClaimToken()), "publication begins once");

        time.addAndGet(100 * worker.getClaimLeaseMillis());
        assertEquals(0, other.reclaimStuckJobs(new Date(time.get()), 0));
        assertNull(other.claimNextPending("other"));
        assertFalse(other.markFailed(claim.getId(), "another-token", "failure"));
        assertTrue(worker.markComplete(claim.getId(), owner, claim.getClaimToken(), new byte[]{9}));
    }

    @Test
    default void pendingDocumentAFailedDocumentCanNeverComplete() {
        final String owner = RecordIds.newId();
        submit(owner, new byte[]{1});
        final PendingDocumentEntity claim = storage().pendingDocuments().claimNextPending("worker");
        assertTrue(storage().pendingDocuments().beginPublication(claim.getId(), claim.getClaimToken()));
        assertTrue(storage().pendingDocuments().markFailed(claim.getId(), claim.getClaimToken(), "failed publication"));
        assertFalse(storage().pendingDocuments().markComplete(claim.getId(), owner, claim.getClaimToken(), new byte[]{9}));
        assertFalse(storage().pendingDocuments().markFailed(claim.getId(), claim.getClaimToken(), "duplicate"));
        assertEquals(PendingDocumentEntity.STATUS_FAILED, storage().anotherInstance().pendingDocuments()
                .findOneByDocumentIdAndUserId(claim.getDocumentId(), owner).getStatus());
    }

    @Test
    default void pendingDocumentReclaimingAndPublishingHaveExactlyOneWinner() throws Exception {
        final String owner = RecordIds.newId();
        for (int iteration = 0; iteration < 20; iteration++) {
            submit(owner, new byte[]{1});
            final PendingDocumentEntity claim = storage().pendingDocuments().claimNextPending("worker");
            final AtomicInteger next = new AtomicInteger();
            // The reclaimer's clock reaches the lease's end while the publisher still sees it live.
            final List<Long> outcome = StorageContract.concurrently(2, () -> next.getAndIncrement() == 0
                    ? (storage().pendingDocuments().beginPublication(claim.getId(), claim.getClaimToken()) ? 1L : 0L)
                    : storage().anotherInstance().pendingDocuments().reclaimStuckJobs(claim.getClaimExpiresAt(), 3));
            assertEquals(1, outcome.get(0) + outcome.get(1), "publication or reclaim, never both or neither");
            if (!storage().pendingDocuments().markFailed(claim.getId(), claim.getClaimToken(), "finished")) {
                final PendingDocumentEntity replacement = storage().pendingDocuments().claimNextPending("replacement");
                assertNotNull(replacement);
                assertTrue(storage().pendingDocuments().markFailed(replacement.getId(), replacement.getClaimToken(), "finished"));
            }
        }
    }

    @Test
    default void pendingDocumentADocumentAbandonedTooOftenFailsAndIsStillNotified() {
        final AtomicLong time = new AtomicLong(System.currentTimeMillis());
        final PendingDocumentDataService jobs = storage().pendingDocumentsAt(time::get);
        final String owner = RecordIds.newId();
        jobs.save(job(owner, 1));
        for (int reclaim = 0; reclaim < 2; reclaim++) {
            assertNotNull(jobs.claimNextPending("worker that dies"));
            time.addAndGet(2 * jobs.getClaimLeaseMillis());
            assertEquals(1, jobs.reclaimStuckJobs(new Date(time.get()), 2));
        }
        assertNotNull(jobs.claimNextPending("worker that dies"));
        time.addAndGet(2 * jobs.getClaimLeaseMillis());
        assertEquals(0, jobs.reclaimStuckJobs(new Date(time.get()), 2), "past the limit it is not returned to the queue");
        assertNull(jobs.claimNextPending("worker"));

        final List<PendingDocumentEntity> notifications = storage().anotherInstance().pendingDocuments().pendingNotifications(10);
        assertEquals(1, notifications.size(), "its owner is still told");
        assertEquals(PendingDocumentEntity.STATUS_FAILED, notifications.get(0).getStatus());
    }

    @Test
    default void pendingDocumentAFinishedDocumentIsKeptUntilItsOwnerHasBeenNotified() {
        final String owner = RecordIds.newId();
        final String documentId = submit(owner, new byte[]{1});
        final PendingDocumentEntity claim = storage().pendingDocuments().claimNextPending("worker");
        assertTrue(storage().pendingDocuments().beginPublication(claim.getId(), claim.getClaimToken()));
        assertTrue(storage().pendingDocuments().markComplete(claim.getId(), owner, claim.getClaimToken(), new byte[]{9}));

        assertEquals(List.of(claim.getId()), storage().pendingDocuments().pendingNotifications(10).stream()
                .map(PendingDocumentEntity::getId).toList());
        final QueueCapacityException refused = assertThrows(QueueCapacityException.class,
                () -> storage().pendingDocuments().deleteByDocumentIdAndUserId(documentId, owner));
        assertEquals(409, refused.getStatus(), "not deleted while its notification is pending");

        storage().pendingDocuments().recordNotificationAttempt(claim.getId());
        assertEquals(1, storage().pendingDocuments().pendingNotifications(10).size(), "an attempt is not an acknowledgement");
        storage().anotherInstance().pendingDocuments().acknowledgeNotification(claim.getId());
        storage().pendingDocuments().acknowledgeNotification(claim.getId());
        assertTrue(storage().pendingDocuments().pendingNotifications(10).isEmpty());
        assertEquals(1, storage().pendingDocuments().deleteByDocumentIdAndUserId(documentId, owner));
        assertNull(storage().pendingDocuments().findOneByDocumentIdAndUserId(documentId, owner));
    }

    @Test
    default void pendingDocumentTheQueueLimitsAreEnforcedAndOwnersTakeTurns() {
        try (MockedStatic<EnvUtils> env = mockStatic(EnvUtils.class, CALLS_REAL_METHODS)) {
            env.when(() -> EnvUtils.getInt("ASYNC_QUEUE_MAX_USER_JOBS", 32)).thenReturn(2);
            env.when(() -> EnvUtils.getLong("ASYNC_QUEUE_MAX_USER_BYTES", 256L * 1024 * 1024)).thenReturn(20L);
            final PendingDocumentDataService jobs = storage().pendingDocumentsAt(System::currentTimeMillis);
            final String owner = RecordIds.newId();
            jobs.save(job(owner, 10));
            jobs.save(job(owner, 10));
            assertEquals(429, assertThrows(QueueCapacityException.class, () -> jobs.save(job(owner, 10))).getStatus());

            final String other = RecordIds.newId();
            jobs.save(job(other, 10));
            final PendingDocumentEntity first = jobs.claimNextPending("worker");
            assertEquals(owner, first.getUserId());
            assertEquals(other, jobs.claimNextPending("worker").getUserId(),
                    "another owner runs before the first owner's second document");

            assertTrue(jobs.markFailed(first.getId(), first.getClaimToken(), "test"));
            jobs.save(job(owner, 10));
            assertEquals(429, assertThrows(QueueCapacityException.class, () -> jobs.save(job(RecordIds.newId(), 21)))
                    .getStatus(), "too large for any owner's share");
            assertEquals(3, jobs.queueStats(null).jobs());
            assertEquals(30, jobs.queueStats(null).bytes());
        }
    }

    @Test
    default void pendingDocumentConcurrentSubmissionsNeverExceedTheQueueLimits() throws Exception {
        try (MockedStatic<EnvUtils> env = mockStatic(EnvUtils.class, CALLS_REAL_METHODS)) {
            env.when(() -> EnvUtils.getInt("ASYNC_QUEUE_MAX_JOBS", 256)).thenReturn(2);
            env.when(() -> EnvUtils.getInt("ASYNC_QUEUE_MAX_USER_JOBS", 32)).thenReturn(1);
            final List<PendingDocumentDataService> instances = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                instances.add((i % 2 == 0 ? storage() : storage().anotherInstance()).pendingDocumentsAt(System::currentTimeMillis));
            }
            final String full = RecordIds.newId();
            instances.get(0).save(job(full, 1));
            final AtomicInteger next = new AtomicInteger();
            final List<Integer> statuses = StorageContract.concurrently(8, () -> {
                final int i = next.getAndIncrement();
                try {
                    instances.get(i).save(job(i == 0 ? full : RecordIds.newId(), 1));
                    return 200;
                } catch (final QueueCapacityException refused) {
                    return refused.getStatus();
                }
            });
            assertTrue(instances.get(0).queueStats(null).jobs() <= 2, "the global limit holds: " + statuses);
            assertEquals(1, instances.get(0).queueStats(full).jobs(), "the owner's limit holds");
            assertEquals(1, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
        }
    }

}
