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
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.policies.EffectiveConfigurationLimits;
import ai.philterd.philter.utils.EnvUtils;

import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The rules for the asynchronous queue, the same in every storage implementation: the per-account and global
 * limits and their admission guard, the fairness rounds that keep one account from starving the others, the
 * claim lease, the checks on a submitted configuration, and how long finished documents are kept. An
 * implementation stores the documents and makes the claims and fenced transitions atomic.
 */
public abstract class StoredPendingDocumentDataService implements PendingDocumentDataService {

    private static final long DEFAULT_RETENTION_SECONDS = 7L * 24L * 60L * 60L;

    /** Preserves the ten minute lease deployments had before it became configurable. */
    protected static final int DEFAULT_CLAIM_LEASE_SECONDS = 600;

    protected final EncryptionService encryptionService;
    protected final LongSupplier clock;
    private final long claimLeaseMillis;
    private final AdmissionGuard admission;
    private final int maxJobs = EnvUtils.getInt("ASYNC_QUEUE_MAX_JOBS", 256);
    private final int maxUserJobs = EnvUtils.getInt("ASYNC_QUEUE_MAX_USER_JOBS", 32);
    private final long maxBytes = EnvUtils.getLong("ASYNC_QUEUE_MAX_BYTES", 2L * 1024 * 1024 * 1024);
    private final long maxUserBytes = EnvUtils.getLong("ASYNC_QUEUE_MAX_USER_BYTES", 256L * 1024 * 1024);

    protected StoredPendingDocumentDataService(final EncryptionService encryptionService, final LongSupplier clock,
                                               final int claimLeaseSeconds, final AdmissionGuard admission) {
        this.encryptionService = encryptionService;
        this.clock = clock;
        if (claimLeaseSeconds <= 0) {
            throw new IllegalArgumentException("DOCUMENT_CLAIM_LEASE_SECONDS must be positive.");
        }
        this.claimLeaseMillis = claimLeaseSeconds * 1000L;
        if (maxJobs <= 0 || maxUserJobs <= 0 || maxBytes <= 0 || maxUserBytes <= 0) {
            throw new IllegalArgumentException("Async queue limits must be positive.");
        }
        this.admission = admission;
    }

    /** The claim lease from {@code DOCUMENT_CLAIM_LEASE_SECONDS}. */
    protected static int claimLeaseSeconds() {
        return EnvUtils.getInt("DOCUMENT_CLAIM_LEASE_SECONDS", DEFAULT_CLAIM_LEASE_SECONDS);
    }

    /** How long a finished document is kept, after its notification is acknowledged, before it is removed. */
    protected static long retentionSeconds() {
        return EnvUtils.getLong("PENDING_DOCUMENTS_TTL_SECONDS", DEFAULT_RETENTION_SECONDS);
    }

    /**
     * A document being admitted with its secrets already encrypted: its resolved configuration, input, and output.
     * Each is encrypted once, and every step of admission stores that ciphertext.
     */
    public record SealedJob(PendingDocumentEntity entity, SecretFields.Sealed effective, SecretFields.SealedBytes input,
                            SecretFields.SealedBytes output) {
    }

    /** Encrypts the document's secrets once, for every step of its admission. */
    protected final SealedJob seal(final PendingDocumentEntity entity) {
        return new SealedJob(entity,
                SecretFields.seal(encryptionService, entity.getEffectiveJson(), entity.getUserId()),
                SecretFields.sealBytes(encryptionService, entity.getInput(), entity.getUserId()),
                SecretFields.sealBytes(encryptionService, entity.getOutput(), entity.getUserId()));
    }

    /**
     * Refuses a document too large to store, with {@code PayloadTooLargeException}. Checked before anything is
     * written.
     */
    protected abstract void requireStorable(final SealedJob job);

    /** Keeps the document's effective configuration, encrypted, by its owner and hash, unless already kept. */
    protected abstract void retainSnapshot(final SealedJob job);

    /** The fairness round of the last document claimed, or 0. */
    protected abstract long globalRound();

    /** The fairness round of the owner's last admitted document, or 0. */
    protected abstract long ownerRound(final String userId);

    /** Records the owner's last admitted round. */
    protected abstract void setOwnerRound(final String userId, final long round);

    /** Stores an admitted document in this fairness round, and returns its id. */
    protected abstract String insert(final SealedJob job, final long round);

    /**
     * Claims the pending document with the lowest fairness round, oldest first, for this worker with this token
     * and lease, and raises the global round to its round. Returns null when none is pending. Concurrent calls
     * never claim the same document.
     */
    protected abstract PendingDocumentEntity claim(final String workerId, final String claimToken, final Date now,
            final Date claimExpiresAt);

    /** Moves the lease to {@code claimExpiresAt}, only while the token's claim is live and not publishing. */
    protected abstract boolean renew(final String id, final String claimToken, final Date now, final Date claimExpiresAt);

    /** Marks publication started, only while the token's claim is live and not already publishing. */
    protected abstract boolean begin(final String id, final String claimToken, final Date now);

    /**
     * Completes a document that is publishing under this token: stores its output, drops its input, and marks its
     * notification pending.
     */
    protected abstract boolean complete(final String id, final String userId, final String claimToken,
            final SecretFields.SealedBytes output, final Date now);

    /**
     * Fails a document under this token, while it is publishing or its claim is live: records the error, drops
     * its input, and marks its notification pending.
     */
    protected abstract boolean fail(final String id, final String claimToken, final String errorMessage, final Date now);

    /** Deletes the owner's documents with this id whose notification is not pending, and returns how many. */
    protected abstract long deleteNotified(final String documentId, final String userId);

    /** Whether the owner has a document with this id whose notification is pending. */
    protected abstract boolean hasPendingNotification(final String documentId, final String userId);

    @Override
    public long getClaimLeaseMillis() {
        return claimLeaseMillis;
    }

    @Override
    public String save(final PendingDocumentEntity entity) {
        EffectiveConfigurationLimits.requireSize(entity.getEffectiveJson());
        if (entity.getEffectiveJson() != null && !Objects.equals(entity.getEffectiveHash(),
                PolicyVersionDataService.contentHash(entity.getEffectiveJson()))) {
            throw new IllegalArgumentException("Effective configuration fingerprint mismatch.");
        }
        final SealedJob sealed = seal(entity);
        requireStorable(sealed);
        // Immutable snapshots are safe to retain before admission, even if admission later fails.
        // A snapshot failure must never strand the global queue guard.
        if (entity.getEffectiveJson() != null) {
            retainSnapshot(sealed);
        }
        return admission.execute(() -> {
            final QueueStats global = queueStats(null);
            final QueueStats owner = queueStats(entity.getUserId());
            final long size = entity.getInput() == null ? 0 : entity.getInput().length;
            if (owner.jobs() >= maxUserJobs || size > maxUserBytes - owner.bytes()) {
                throw new QueueCapacityException("Account async queue limit reached.", 429);
            }
            if (global.jobs() >= maxJobs || size > maxBytes - global.bytes()) {
                throw new QueueCapacityException("Global async queue limit reached.", 503);
            }
            final long round = Math.max(globalRound(), ownerRound(entity.getUserId())) + 1;
            setOwnerRound(entity.getUserId(), round);
            return insert(sealed, round);
        });
    }

    @Override
    public PendingDocumentEntity claimNextPending(final String workerId) {
        final Date now = new Date(clock.getAsLong());
        return claim(workerId, UUID.randomUUID().toString(), now, new Date(now.getTime() + claimLeaseMillis));
    }

    @Override
    public boolean renewClaim(final String id, final String claimToken) {
        final long now = clock.getAsLong();
        return renew(id, requireToken(claimToken), new Date(now), new Date(now + claimLeaseMillis));
    }

    @Override
    public boolean beginPublication(final String id, final String claimToken) {
        return begin(id, requireToken(claimToken), new Date(clock.getAsLong()));
    }

    @Override
    public boolean markComplete(final String id, final String userId, final String claimToken, final byte[] output) {
        requireToken(claimToken);
        return complete(id, userId, claimToken, SecretFields.sealBytes(encryptionService, output, userId), new Date());
    }

    @Override
    public boolean markFailed(final String id, final String claimToken, final String errorMessage) {
        return fail(id, requireToken(claimToken), errorMessage, new Date());
    }

    @Override
    public long deleteByDocumentIdAndUserId(final String documentId, final String userId) {
        final long deleted = deleteNotified(documentId, userId);
        if (deleted == 0 && hasPendingNotification(documentId, userId)) {
            throw new QueueCapacityException("Notification dispatch is pending. Retry deletion later.", 409);
        }
        return deleted;
    }

    private static String requireToken(final String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("A job claim token is required.");
        }
        return claimToken;
    }

}
