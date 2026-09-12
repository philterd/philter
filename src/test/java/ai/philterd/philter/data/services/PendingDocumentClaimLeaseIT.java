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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The configurable claim lease (philterd/philter#97). The fencing tests inject a clock, which no
 * deployment can do; this uses a lease short enough to wait out for real, which is what the
 * environment variable makes possible outside the process.
 */
class PendingDocumentClaimLeaseIT extends AbstractMongoIT {

    private static final int SHORT_LEASE_SECONDS = 1;

    private final ObjectId user = new ObjectId();

    private PendingDocumentDataService service(final int leaseSeconds) {
        return new PendingDocumentDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), System::currentTimeMillis, leaseSeconds);
    }

    private String submit(final PendingDocumentDataService service) {
        final PendingDocumentEntity job = new PendingDocumentEntity();
        job.setUserId(user);
        job.setDocumentId(new ObjectId().toHexString());
        job.setStatus(PendingDocumentEntity.STATUS_PENDING);
        job.setSubmittedAt(new Date());
        job.setInput(new byte[]{1});
        service.save(job);
        return job.getDocumentId();
    }

    @Test
    @DisplayName("The default lease is ten minutes, so existing deployments are unaffected")
    void theDefaultLeaseIsUnchanged() {
        final PendingDocumentDataService service = new PendingDocumentDataService(
                mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
        assertEquals(600_000L, service.getClaimLeaseMillis());
    }

    @Test
    @DisplayName("A non-positive lease is rejected at construction")
    void aNonPositiveLeaseIsRejected() {
        for (final int invalid : new int[]{0, -1}) {
            final IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, () -> service(invalid),
                            "a lease of " + invalid + " must be refused");
            assertTrue(thrown.getMessage().contains("DOCUMENT_CLAIM_LEASE_SECONDS"),
                    "the message must name the setting: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("A shortened lease is the one actually written onto a claim")
    void theConfiguredLeaseGovernsTheClaim() {
        final PendingDocumentDataService service = service(SHORT_LEASE_SECONDS);
        submit(service);

        final PendingDocumentEntity claimed = service.claimNextPending("worker-1");
        assertNotNull(claimed);

        final long lease = claimed.getClaimExpiresAt().getTime() - claimed.getClaimedAt().getTime();
        assertEquals(SHORT_LEASE_SECONDS * 1000L, lease,
                "the claim must expire after the configured lease, not the built-in default");
    }

    @Test
    @DisplayName("A job whose worker stops is taken over by another instance and completes once")
    void anAbandonedJobIsTakenOverAndCompletesOnce() throws Exception {

        final PendingDocumentDataService stopping = service(SHORT_LEASE_SECONDS);
        final PendingDocumentDataService surviving = service(SHORT_LEASE_SECONDS);

        final String documentId = submit(stopping);

        // The first worker claims the job and then stops: it never renews, completes, or fails.
        final PendingDocumentEntity abandoned = stopping.claimNextPending("worker-that-stops");
        assertNotNull(abandoned);

        // Nothing to take over while the lease is live.
        assertEquals(0, surviving.reclaimStuckJobs(new Date(), 3));
        assertNull(surviving.claimNextPending("worker-that-survives"));

        // Wait the real lease out, rather than moving an injected clock.
        Thread.sleep(SHORT_LEASE_SECONDS * 1000L + 250L);

        assertEquals(1, surviving.reclaimStuckJobs(new Date(), 3));

        final PendingDocumentEntity takenOver = surviving.claimNextPending("worker-that-survives");
        assertNotNull(takenOver, "another instance must be able to claim the abandoned job");
        assertNotEquals(abandoned.getClaimToken(), takenOver.getClaimToken(),
                "the takeover must fence the stopped worker's token");

        assertTrue(surviving.beginPublication(takenOver.getId(), takenOver.getClaimToken()));
        assertTrue(surviving.markComplete(takenOver.getId(), user, takenOver.getClaimToken(), new byte[]{9}));

        // Completes once: the stopped worker cannot publish a second result if it ever wakes up.
        assertFalse(stopping.markComplete(abandoned.getId(), user, abandoned.getClaimToken(), new byte[]{1}));
        assertFalse(stopping.markFailed(abandoned.getId(), abandoned.getClaimToken(), "late failure"));

        final PendingDocumentEntity finished = surviving.findOneByDocumentIdAndUserId(documentId, user);
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, finished.getStatus());
        assertArrayEquals(new byte[]{9}, finished.getOutput(),
                "the surviving worker's output must be the one that stands");

    }

}
