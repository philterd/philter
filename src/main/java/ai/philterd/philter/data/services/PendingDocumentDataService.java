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

import ai.philterd.philter.data.entities.PendingDocumentEntity;

import java.util.Date;
import java.util.List;

/**
 * The queue of documents submitted for asynchronous redaction, and their results.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>{@link #claimNextPending} gives each pending document to at most one worker at a time: concurrent
 *   workers never claim the same document. A claim holds a lease; a document whose lease expires can be
 *   claimed again, and a completed or failed document is never claimed.</li>
 *   <li>A worker's writes to a document take effect only while it still holds the claim, so a worker whose
 *   lease was taken over cannot overwrite the new claimant's work.</li>
 *   <li>The queue's per-user and global limits hold even when documents are submitted concurrently.</li>
 *   <li>Finished documents are removed after their retention period; nothing else expires on its own.</li>
 *   <li>Inputs and outputs are stored encrypted, and what is written is read back unchanged.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping documents, and sort by the
 *   fields the API offers (see {@link Listings}).</li>
 * </ul>
 */
public interface PendingDocumentDataService {

    /** How long a worker owns a claim, in milliseconds. */
    long getClaimLeaseMillis();

    String save(final PendingDocumentEntity entity);

    /** How many jobs are queued or running, their size in bytes, and the oldest one's age in seconds. */
    record QueueStats(long jobs, long bytes, long oldestAgeSeconds) { }

    QueueStats queueStats(final String owner);

    PendingDocumentEntity findOneByDocumentIdAndUserId(final String documentId, final String userId);

    /**
     * A page of the user's queued documents, with the given status when one is given, and their total.
     * With {@code q}, only documents whose document ID or file name contains it, ignoring case.
     * The documents themselves are left out: a listing has no use for them, and they are large.
     */
    Listings.Page<PendingDocumentEntity> list(final String userId, final String status, final String q,
            final Listings.Sort sort, final int offset, final int limit);

    PendingDocumentEntity claimNextPending(final String workerId);

    /**
     * Returns expired computation claims to the queue, up to {@code maxReclaims} times.
     * Claims that entered publication are excluded even when their computation lease has expired.
     *
     * <p>Bounded on purpose. A document that reliably kills the worker (an out-of-memory on a large
     * PDF, a malformed file that crashes the parser) would otherwise cycle pending, processing,
     * reclaimed forever, never reaching a terminal state. Its {@code completed_at} would never be
     * set, so its retention period would never start and the submitted document would be kept indefinitely.
     *
     * @return the number of jobs returned to the queue
     */
    long reclaimStuckJobs(final Date now, final int maxReclaims);

    boolean renewClaim(final String id, final String claimToken);

    /**
     * Fences publication before evidence, metrics, or completion audit events are written.
     * This phase never expires: reclaiming it could race an already-issued external write.
     * An interrupted publisher requires operator recovery after the old worker is stopped.
     */
    boolean beginPublication(final String id, final String claimToken);

    boolean markComplete(final String id, final String userId, final String claimToken, final byte[] output);

    boolean markFailed(final String id, final String claimToken, final String errorMessage);

    long deleteByDocumentIdAndUserId(final String documentId, final String userId);

    boolean hasOpenJobsForContext(final String userId, final String contextName);

    List<PendingDocumentEntity> pendingNotifications(final int limit);

    void recordNotificationAttempt(final String jobId);

    void acknowledgeNotification(final String jobId);

}
