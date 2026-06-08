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
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link PendingDocumentDataService} against a real (in-memory) MongoDB.
 * These exercise the actual {@code findOneAndUpdate}-based atomic claim, the stuck-job reclaim
 * query with explicit {@link Date} boundaries, the complete/fail status transitions (including the
 * {@code input} unset and {@code output}/{@code completed_at} markers), user/context scoping, the
 * pending/processing counts, and deletes end to end — behavior the mock-based unit tests can only
 * approximate.
 */
class PendingDocumentDataServiceIT extends AbstractMongoIT {

    private PendingDocumentDataService service;

    @BeforeEach
    void setUpService() {
        service = new PendingDocumentDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    private PendingDocumentEntity newPending(final ObjectId userId, final String documentId) {
        return newPending(userId, documentId, "default", new Date());
    }

    private PendingDocumentEntity newPending(final ObjectId userId, final String documentId,
                                             final String contextName, final Date submittedAt) {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setContextName(contextName);
        entity.setPolicyName("default");
        entity.setInputMimeType("APPLICATION_PDF");
        entity.setOutputMimeType("APPLICATION_PDF");
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        entity.setInput(new byte[]{1, 2, 3});
        entity.setSubmittedAt(submittedAt);
        return entity;
    }

    @Test
    void findOneByDocumentIdAndUserIdScopesToOwner() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.save(newPending(userA, "doc-1"));

        final PendingDocumentEntity found = service.findOneByDocumentIdAndUserId("doc-1", userA);
        assertNotNull(found);
        assertEquals("doc-1", found.getDocumentId());
        assertEquals(userA, found.getUserId());

        // A different user cannot see the document, and an unknown id returns null.
        assertNull(service.findOneByDocumentIdAndUserId("doc-1", userB));
        assertNull(service.findOneByDocumentIdAndUserId("missing", userA));
    }

    @Test
    void findAllByUserIdPagesNewestFirstAndScopesByUser() {
        final ObjectId user = new ObjectId();
        final ObjectId other = new ObjectId();
        final long base = System.currentTimeMillis();
        // Insert out of submitted_at order to prove the descending sort.
        service.save(newPending(user, "old", "default", new Date(base)));
        service.save(newPending(user, "mid", "default", new Date(base + 1000)));
        service.save(newPending(user, "new", "default", new Date(base + 2000)));
        service.save(newPending(other, "other", "default", new Date(base + 3000)));

        final List<PendingDocumentEntity> page = service.findAllByUserId(user, 0, 2);
        assertEquals(2, page.size());
        assertEquals("new", page.get(0).getDocumentId());
        assertEquals("mid", page.get(1).getDocumentId());

        final List<PendingDocumentEntity> next = service.findAllByUserId(user, 2, 2);
        assertEquals(1, next.size());
        assertEquals("old", next.get(0).getDocumentId());
    }

    @Test
    void claimNextPendingMarksJobSoSecondClaimDoesNotReturnIt() {
        final ObjectId user = new ObjectId();
        final long base = System.currentTimeMillis();
        service.save(newPending(user, "first", "default", new Date(base)));
        service.save(newPending(user, "second", "default", new Date(base + 1000)));

        // The oldest pending job is claimed first and transitioned to PROCESSING.
        final PendingDocumentEntity firstClaim = service.claimNextPending("worker-1");
        assertNotNull(firstClaim);
        assertEquals("first", firstClaim.getDocumentId());
        assertEquals(PendingDocumentEntity.STATUS_PROCESSING, firstClaim.getStatus());
        assertEquals("worker-1", firstClaim.getClaimedBy());
        assertNotNull(firstClaim.getClaimedAt());
        assertNotNull(firstClaim.getStartedAt());

        // A second claim must return the other job, not the one already claimed.
        final PendingDocumentEntity secondClaim = service.claimNextPending("worker-2");
        assertNotNull(secondClaim);
        assertEquals("second", secondClaim.getDocumentId());
        assertNotEquals(firstClaim.getId(), secondClaim.getId());

        // No pending jobs remain, so a third claim returns null.
        assertNull(service.claimNextPending("worker-3"));
    }

    @Test
    void concurrentClaimsNeverHandTheSameJobToTwoWorkers() throws Exception {
        // The multi-instance coordination guarantee: many workers polling at once must never both claim
        // the same job. This exercises the atomic findOneAndUpdate under real parallelism.
        final ObjectId user = new ObjectId();
        final int jobCount = 60;
        final long base = System.currentTimeMillis();
        for (int i = 0; i < jobCount; i++) {
            service.save(newPending(user, "doc-" + i, "default", new Date(base + i)));
        }

        final int workers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(workers);
        final CountDownLatch start = new CountDownLatch(1);
        final Queue<ObjectId> claimed = new ConcurrentLinkedQueue<>();
        final List<Future<Integer>> futures = new ArrayList<>();

        for (int w = 0; w < workers; w++) {
            final String workerId = "worker-" + w;
            final Callable<Integer> task = () -> {
                start.await();
                int count = 0;
                PendingDocumentEntity job;
                while ((job = service.claimNextPending(workerId)) != null) {
                    claimed.add(job.getId());
                    count++;
                }
                return count;
            };
            futures.add(pool.submit(task));
        }

        start.countDown(); // release all workers at once
        for (final Future<Integer> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // Every job was claimed exactly once: no job handed to two workers, and none missed.
        final Set<ObjectId> unique = new HashSet<>(claimed);
        assertEquals(jobCount, claimed.size(), "no job may be claimed more than once (no double-processing)");
        assertEquals(jobCount, unique.size(), "claimed job ids must all be distinct");

        // And nothing remains claimable.
        assertNull(service.claimNextPending("drain"));
    }

    @Test
    void reclaimStuckJobsResetsOnlyJobsClaimedBeforeTheCutoff() {
        final ObjectId user = new ObjectId();
        service.save(newPending(user, "stuck"));
        service.save(newPending(user, "fresh"));

        // Claim both so they are PROCESSING; capture their claimed_at timestamps.
        final PendingDocumentEntity stuck = service.claimNextPending("w");
        final PendingDocumentEntity fresh = service.claimNextPending("w");
        assertNotNull(stuck);
        assertNotNull(fresh);

        // A cutoff before any claim time reclaims nothing (the "not yet stuck" branch).
        final Date beforeAnyClaim = new Date(stuck.getClaimedAt().getTime() - 60_000);
        assertEquals(0L, service.reclaimStuckJobs(beforeAnyClaim));
        assertEquals(PendingDocumentEntity.STATUS_PROCESSING,
                service.findOneByDocumentIdAndUserId("stuck", user).getStatus());

        // A cutoff in the future reclaims every PROCESSING job (the "stuck" branch).
        final Date future = new Date(System.currentTimeMillis() + 60_000);
        assertEquals(2L, service.reclaimStuckJobs(future));

        final PendingDocumentEntity reclaimed = service.findOneByDocumentIdAndUserId("stuck", user);
        assertEquals(PendingDocumentEntity.STATUS_PENDING, reclaimed.getStatus());
        assertNull(reclaimed.getClaimedBy());
        assertNull(reclaimed.getClaimedAt());
        assertNull(reclaimed.getStartedAt());

        // Reclaimed jobs become claimable again.
        assertNotNull(service.claimNextPending("w2"));
    }

    @Test
    void markCompleteSetsOutputCompletedAtAndClearsInput() {
        final ObjectId user = new ObjectId();
        service.save(newPending(user, "doc-1"));
        final PendingDocumentEntity claimed = service.claimNextPending("w");
        assertNotNull(claimed.getInput());

        final byte[] output = new byte[]{9, 8, 7};
        service.markComplete(claimed.getId(), output);

        final PendingDocumentEntity completed = service.findOneByDocumentIdAndUserId("doc-1", user);
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, completed.getStatus());
        assertArrayEquals(output, completed.getOutput());
        assertNotNull(completed.getCompletedAt());
        assertNull(completed.getInput(), "input must be unset on completion");
    }

    @Test
    void markFailedSetsErrorCompletedAtAndClearsInput() {
        final ObjectId user = new ObjectId();
        service.save(newPending(user, "doc-1"));
        final PendingDocumentEntity claimed = service.claimNextPending("w");

        service.markFailed(claimed.getId(), "boom");

        final PendingDocumentEntity failed = service.findOneByDocumentIdAndUserId("doc-1", user);
        assertEquals(PendingDocumentEntity.STATUS_FAILED, failed.getStatus());
        assertEquals("boom", failed.getErrorMessage());
        assertNotNull(failed.getCompletedAt());
        assertNull(failed.getInput(), "input must be unset on failure");
    }

    @Test
    void deleteByDocumentIdAndUserIdScopesToOwner() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.save(newPending(userA, "doc-1"));

        // A different user cannot delete the document.
        assertEquals(0L, service.deleteByDocumentIdAndUserId("doc-1", userB));
        assertNotNull(service.findOneByDocumentIdAndUserId("doc-1", userA));

        // The owning user can.
        assertEquals(1L, service.deleteByDocumentIdAndUserId("doc-1", userA));
        assertNull(service.findOneByDocumentIdAndUserId("doc-1", userA));
    }

    @Test
    void countByUserIdCountsAllStatusesScopedToUser() {
        final ObjectId user = new ObjectId();
        final ObjectId other = new ObjectId();
        service.save(newPending(user, "a"));
        service.save(newPending(user, "b"));
        service.save(newPending(other, "c"));

        assertEquals(2, service.countByUserId(user));
        assertEquals(1, service.countByUserId(other));
        assertEquals(0, service.countByUserId(new ObjectId()));
    }

    @Test
    void countPendingByUserIdCountsOnlyPendingAndProcessing() {
        final ObjectId user = new ObjectId();
        service.save(newPending(user, "pending"));
        service.save(newPending(user, "processing"));
        service.save(newPending(user, "complete"));

        // Move one to PROCESSING and one to COMPLETE.
        final PendingDocumentEntity processing = service.claimNextPending("w");
        service.markComplete(service.claimNextPending("w").getId(), new byte[]{1});
        assertNotNull(processing);

        // One PENDING + one PROCESSING are counted; the COMPLETE one is not.
        assertEquals(2, service.countPendingByUserId(user));
    }

    @Test
    void hasOpenJobsForContextReflectsPendingProcessingAndTerminalStates() {
        final ObjectId user = new ObjectId();
        service.save(newPending(user, "doc-1", "ctx", new Date()));

        // A PENDING job in the context counts as open.
        assertTrue(service.hasOpenJobsForContext(user, "ctx"));
        // A different context, and a different user, see no open jobs.
        assertFalse(service.hasOpenJobsForContext(user, "other"));
        assertFalse(service.hasOpenJobsForContext(new ObjectId(), "ctx"));

        // PROCESSING still counts as open.
        final PendingDocumentEntity claimed = service.claimNextPending("w");
        assertTrue(service.hasOpenJobsForContext(user, "ctx"));

        // Once terminal (COMPLETE), the context has no open jobs.
        service.markComplete(claimed.getId(), new byte[]{1});
        assertFalse(service.hasOpenJobsForContext(user, "ctx"));
    }

}
