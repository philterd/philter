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
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PendingDocumentFencingIT extends AbstractMongoIT {
    private final AtomicLong time = new AtomicLong(System.currentTimeMillis());
    private final ObjectId user = new ObjectId();
    private PendingDocumentDataService first;
    private PendingDocumentDataService second;

    @BeforeEach
    void services() {
        first = service();
        second = service();
    }

    private PendingDocumentDataService service() {
        return new PendingDocumentDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), time::get);
    }

    private PendingDocumentEntity claim() {
        final var job = new PendingDocumentEntity();
        job.setUserId(user);
        job.setDocumentId(new ObjectId().toHexString());
        job.setStatus(PendingDocumentEntity.STATUS_PENDING);
        job.setSubmittedAt(new Date(time.get()));
        job.setInput(new byte[]{1});
        first.save(job);
        return first.claimNextPending("same-worker-id");
    }

    @Test
    void supersededAttemptCannotPublishCompleteFailOrRenewEvenWithSameWorkerId() {
        final var old = claim();
        time.addAndGet(first.getClaimLeaseMillis());
        assertFalse(first.beginPublication(old.getId(), old.getClaimToken()));
        assertFalse(first.renewClaim(old.getId(), old.getClaimToken()));
        assertFalse(first.markFailed(old.getId(), old.getClaimToken(), "expired"));
        assertEquals(1, second.reclaimStuckJobs(new Date(time.get()), 3));
        final var current = second.claimNextPending("same-worker-id");
        assertNotEquals(old.getClaimToken(), current.getClaimToken());
        assertFalse(first.beginPublication(old.getId(), old.getClaimToken()));
        assertFalse(first.markComplete(old.getId(), user, old.getClaimToken(), new byte[]{0}));
        assertFalse(first.markFailed(old.getId(), old.getClaimToken(), "late failure"));
        assertTrue(second.beginPublication(current.getId(), current.getClaimToken()));
        assertTrue(second.markComplete(current.getId(), user, current.getClaimToken(), new byte[]{9}));
        assertFalse(first.markFailed(old.getId(), old.getClaimToken(), "late failure"));
        assertFalse(first.markComplete(old.getId(), user, old.getClaimToken(), new byte[]{0}));
        assertFalse(second.markFailed(current.getId(), current.getClaimToken(), "duplicate"));
        assertFalse(second.markComplete(current.getId(), user, current.getClaimToken(), new byte[]{0}));
        assertArrayEquals(new byte[]{9}, first.findOneByDocumentIdAndUserId(old.getDocumentId(), user).getOutput());
    }

    @Test
    void renewalKeepsSlowComputationOwnedBeyondInitialTenMinutes() {
        final var job = claim();
        for (int minute = 1; minute <= 25; minute++) {
            time.addAndGet(60_000);
            assertTrue(first.renewClaim(job.getId(), job.getClaimToken()));
            assertEquals(0, second.reclaimStuckJobs(new Date(time.get()), 3));
        }
        assertTrue(first.beginPublication(job.getId(), job.getClaimToken()));
        assertTrue(first.markComplete(job.getId(), user, job.getClaimToken(), new byte[]{9}));
    }

    @Test
    void interruptedPublisherIsNotReclaimedOrFailedByAnotherService() {
        final var job = claim();
        assertFalse(first.markComplete(job.getId(), user, job.getClaimToken(), new byte[]{9}));
        assertTrue(first.beginPublication(job.getId(), job.getClaimToken()));
        assertFalse(first.beginPublication(job.getId(), job.getClaimToken()));
        time.addAndGet(100 * first.getClaimLeaseMillis());
        assertEquals(0, service().reclaimStuckJobs(new Date(time.get()), 0));
        assertNull(second.claimNextPending("other"));
        assertFalse(second.markFailed(job.getId(), "other-token", "failure"));
        assertTrue(first.markComplete(job.getId(), user, job.getClaimToken(), new byte[]{9}));
    }

    @Test
    void failedAttemptIsTerminalAndCannotLaterComplete() {
        final var job = claim();
        assertTrue(first.beginPublication(job.getId(), job.getClaimToken()));
        assertTrue(first.markFailed(job.getId(), job.getClaimToken(), "failed publication"));
        assertFalse(first.markComplete(job.getId(), user, job.getClaimToken(), new byte[]{9}));
        assertFalse(first.markFailed(job.getId(), job.getClaimToken(), "duplicate"));
        assertEquals(PendingDocumentEntity.STATUS_FAILED,
                second.findOneByDocumentIdAndUserId(job.getDocumentId(), user).getStatus());
    }

    @Test
    void reclaimAndPublicationHandoffHaveExactlyOneWinner() throws Exception {
        try (final var threads = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 20; iteration++) {
                final var job = claim();
                final var start = new CountDownLatch(1);
                // A reclaimer's clock reaches expiry while the publisher still sees a live lease.
                final var publication = threads.submit(() -> {
                    start.await(); return first.beginPublication(job.getId(), job.getClaimToken());
                });
                final var reclaim = threads.submit(() -> {
                    start.await(); return second.reclaimStuckJobs(job.getClaimExpiresAt(), 3);
                });
                start.countDown();
                assertEquals(1, (publication.get(5, TimeUnit.SECONDS) ? 1 : 0) + reclaim.get(5, TimeUnit.SECONDS));
                if (first.markFailed(job.getId(), job.getClaimToken(), "finished")) continue;
                final var replacement = second.claimNextPending("replacement");
                assertNotNull(replacement);
                assertTrue(second.markFailed(replacement.getId(), replacement.getClaimToken(), "finished"));
            }
        }
    }
}
