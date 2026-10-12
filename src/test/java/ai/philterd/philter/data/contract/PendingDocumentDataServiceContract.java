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
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

}
