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
import ai.philterd.philter.services.encryption.EncryptedBytes;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.utils.EnvUtils;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

public class PendingDocumentDataService extends AbstractEncryptedService<PendingDocumentEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PendingDocumentDataService.class);

    private static final long DEFAULT_TTL_SECONDS = 7L * 24L * 60L * 60L;

    /** Preserves the ten minute lease deployments had before it became configurable. */
    private static final int DEFAULT_CLAIM_LEASE_SECONDS = 600;

    private final long claimLeaseMillis;
    private final MongoCollection<Document> jobs;
    private final LongSupplier clock;
    private final QueueAdmission admission;
    private final MongoCollection<Document> snapshots;
    private final MongoCollection<Document> scheduling;
    private final int maxJobs = EnvUtils.getInt("ASYNC_QUEUE_MAX_JOBS", 256);
    private final int maxUserJobs = EnvUtils.getInt("ASYNC_QUEUE_MAX_USER_JOBS", 32);
    private final long maxBytes = EnvUtils.getLong("ASYNC_QUEUE_MAX_BYTES", 2L * 1024 * 1024 * 1024);
    private final long maxUserBytes = EnvUtils.getLong("ASYNC_QUEUE_MAX_USER_BYTES", 256L * 1024 * 1024);


    public PendingDocumentDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                      final AuditEventPublisher auditEventPublisher) {
        this(mongoClient, encryptionService, auditEventPublisher, System::currentTimeMillis);
    }

    PendingDocumentDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                               final AuditEventPublisher auditEventPublisher, final LongSupplier clock) {
        this(mongoClient, encryptionService, auditEventPublisher, clock,
                EnvUtils.getInt("DOCUMENT_CLAIM_LEASE_SECONDS", DEFAULT_CLAIM_LEASE_SECONDS));
    }

    /**
     * The lease is a parameter so a test can use a few seconds and watch a real takeover, which the
     * environment variable exists to make possible for a deployment too.
     */
    PendingDocumentDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                               final AuditEventPublisher auditEventPublisher, final LongSupplier clock,
                               final int claimLeaseSeconds) {
        super(mongoClient, "pending_documents", encryptionService, auditEventPublisher);
        this.clock = clock;
        if (claimLeaseSeconds <= 0) {
            throw new IllegalArgumentException("DOCUMENT_CLAIM_LEASE_SECONDS must be positive.");
        }
        this.claimLeaseMillis = claimLeaseSeconds * 1000L;
        if (maxJobs <= 0 || maxUserJobs <= 0 || maxBytes <= 0 || maxUserBytes <= 0) {
            throw new IllegalArgumentException("Async queue limits must be positive.");
        }
        admission = new QueueAdmission(mongoClient);
        snapshots = mongoClient.getDatabase("philter").getCollection("execution_snapshots");
        scheduling = mongoClient.getDatabase("philter").getCollection("queue_scheduling");
        jobs = collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);

        final long ttlSeconds = EnvUtils.getLong("PENDING_DOCUMENTS_TTL_SECONDS", DEFAULT_TTL_SECONDS);

        ensureIndex(
                Indexes.ascending("retention_at"),
                new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));

        RequiredSchema.rejectUnexpectedExpiry(collection, "retention_at");

        LOGGER.info("TTL index on pending_documents.retention_at set to expire after {} seconds.", ttlSeconds);

        // Per-document lookup/delete, the worker's claim scan, and per-user/context counts.
        ensureIndex(Indexes.ascending("user_id", "document_id"));
        ensureIndex(Indexes.ascending("status", "submitted_at"));
        ensureIndex(Indexes.ascending("status", "claim_expires_at"));
        ensureIndex(Indexes.ascending("user_id", "context_name", "status"));
    }

    /** How long a worker owns a claim, in milliseconds. */
    public long getClaimLeaseMillis() {
        return claimLeaseMillis;
    }

    @Override
    public ObjectId save(final PendingDocumentEntity entity) {
        ai.philterd.philter.services.policies.EffectiveConfigurationLimits.requireSize(entity.getEffectiveJson());
        if (entity.getEffectiveJson() != null && !java.util.Objects.equals(entity.getEffectiveHash(),
                PolicyVersionDataService.contentHash(entity.getEffectiveJson()))) {
            throw new IllegalArgumentException("Effective configuration fingerprint mismatch.");
        }
        final Document document = entity.toDocument(encryptionService);
        // Include fields added during admission in the BSON preflight.
        if (!document.containsKey("_id")) document.put("_id", new ObjectId());
        document.put("fairness_round", 0L);
        requireStorable(document);
        // Immutable snapshots are safe to retain before admission, even if admission later fails.
        // A snapshot failure must never strand the global queue guard.
        if (entity.getEffectiveJson() != null) {
            final Document retained = new Document("_id", entity.getUserId() + ":" + entity.getEffectiveHash())
                    .append("user_id", entity.getUserId()).append("effective_hash", entity.getEffectiveHash())
                    .append("effective_json", document.getString("effective_json"))
                    .append("effective_key", document.getString("effective_key"));
            requireStorable(retained);
            snapshots.withWriteConcern(WriteConcern.MAJORITY).updateOne(Filters.eq("_id", retained.getString("_id")),
                    new Document("$setOnInsert", retained), new com.mongodb.client.model.UpdateOptions().upsert(true));
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
            final Document globalRound = scheduling.find(Filters.eq("_id", "global")).first();
            final Document ownerRound = scheduling.find(Filters.eq("_id", entity.getUserId())).first();
            final long round = Math.max(globalRound == null ? 0 : globalRound.getLong("round"),
                    ownerRound == null ? 0 : ownerRound.getLong("round")) + 1;
            scheduling.updateOne(Filters.eq("_id", entity.getUserId()), Updates.set("round", round),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
            document.put("fairness_round", round);
            return jobs.insertOne(document).getInsertedId().asObjectId().getValue();
        });
    }

    /** Leave space below MongoDB's 16 MiB document limit for claim/publication metadata. */
    static final int MAX_ADMISSION_BSON_BYTES = 16 * 1024 * 1024 - 16 * 1024;

    static void requireStorable(final Document document) {
        try (var buffer = new org.bson.io.BasicOutputBuffer();
             var writer = new org.bson.BsonBinaryWriter(buffer)) {
            new org.bson.codecs.DocumentCodec().encode(writer, document,
                    org.bson.codecs.EncoderContext.builder().build());
            if (buffer.getSize() > MAX_ADMISSION_BSON_BYTES) {
                throw new ai.philterd.philter.api.exceptions.PayloadTooLargeException(
                        "Encrypted async job or configuration exceeds the storage size limit.");
            }
        }
    }

    public record QueueStats(long jobs, long bytes, long oldestAgeSeconds) { }
    public QueueStats queueStats(final ObjectId owner) {
        Bson query = Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING);
        if (owner != null) query = Filters.and(query, Filters.eq("user_id", owner));
        long count = 0, bytes = 0, oldest = clock.getAsLong();
        for (Document job : jobs.find(query).projection(com.mongodb.client.model.Projections.include("input_size", "submitted_at"))) {
            count++;
            bytes += ((Number) job.getOrDefault("input_size", 0L)).longValue();
            if (job.getDate("submitted_at") != null) oldest = Math.min(oldest, job.getDate("submitted_at").getTime());
        }
        return new QueueStats(count, bytes, Math.max(0, (clock.getAsLong() - oldest) / 1000));
    }

    @Override
    public void update(final PendingDocumentEntity entity) {
        throw new UnsupportedOperationException("Use fenced job transitions instead of replacing job state.");
    }

    public PendingDocumentEntity findOneByDocumentIdAndUserId(final String documentId, final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("document_id", documentId),
                Filters.eq("user_id", userId)
        );

        final Document document = jobs.find(query).first();
        return document != null ? PendingDocumentEntity.fromDocument(document, encryptionService) : null;

    }

    public List<PendingDocumentEntity> findAllByUserId(final ObjectId userId, final int offset, final int limit) {

        final Bson query = Filters.eq("user_id", userId);

        final FindIterable<Document> documents = jobs.find(query)
                .sort(Sorts.descending("submitted_at"))
                .skip(offset)
                .limit(limit);

        final List<PendingDocumentEntity> entities = new ArrayList<>();
        for (final Document document : documents) {
            entities.add(PendingDocumentEntity.fromDocument(document, encryptionService));
        }
        return entities;

    }

    public PendingDocumentEntity claimNextPending(final String workerId) {

        final Bson query = Filters.eq("status", PendingDocumentEntity.STATUS_PENDING);

        final Date now = new Date(clock.getAsLong());
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_PROCESSING),
                Updates.set("claimed_by", workerId),
                Updates.set("claimed_at", now),
                Updates.set("claim_token", UUID.randomUUID().toString()),
                Updates.set("claim_expires_at", new Date(now.getTime() + claimLeaseMillis)),
                Updates.unset("publication_started_at"),
                Updates.set("started_at", now)
        );

        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending("fairness_round", "submitted_at"))
                .returnDocument(ReturnDocument.AFTER);

        final Document claimed = jobs.findOneAndUpdate(query, update, options);
        if (claimed != null && claimed.getLong("fairness_round") != null) {
            scheduling.updateOne(Filters.eq("_id", "global"), Updates.max("round", claimed.getLong("fairness_round")),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
        }
        return claimed != null ? PendingDocumentEntity.fromDocument(claimed, encryptionService) : null;

    }

    /**
     * Returns expired computation claims to the queue, up to {@code maxReclaims} times.
     * Claims that entered publication are excluded even when their computation lease has expired.
     *
     * <p>Bounded on purpose. A document that reliably kills the worker (an out-of-memory on a large
     * PDF, a malformed file that crashes the parser) would otherwise cycle pending, processing,
     * reclaimed forever, never reaching a terminal state. Its {@code completed_at} would never be
     * set, so the TTL would never fire and the submitted document would be retained indefinitely.
     *
     * @return the number of jobs returned to the queue
     */
    public long reclaimStuckJobs(final Date now, final int maxReclaims) {

        final Bson stuck = Filters.and(
                Filters.eq("status", PendingDocumentEntity.STATUS_PROCESSING),
                Filters.lte("claim_expires_at", now),
                Filters.exists("publication_started_at", false)
        );

        // Past the cap the job is failed with durable notification intent. Acknowledgement starts retention.
        final Bson exhausted = Filters.and(stuck, Filters.gte("reclaim_count", maxReclaims));
        final long failed = jobs.updateMany(exhausted, Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_FAILED),
                Updates.set("error_message", "Abandoned after " + maxReclaims
                        + " attempts; the worker did not complete this document."),
                Updates.set("completed_at", new Date()),
                Updates.set("notification_pending", true),
                Updates.unset("input"),
                Updates.unset("input_encrypted_key"),
                Updates.unset("claimed_by"),
                Updates.unset("claimed_at"),
                Updates.unset("claim_token"),
                Updates.unset("claim_expires_at")
        )).getModifiedCount();

        if (failed > 0) {
            LOGGER.warn("Failed {} job(s) that could not be completed after {} attempts.", failed, maxReclaims);
        }

        final Bson retryable = Filters.and(stuck, Filters.lt("reclaim_count", maxReclaims));
        return jobs.updateMany(retryable, Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_PENDING),
                Updates.inc("reclaim_count", 1),
                Updates.unset("claimed_by"),
                Updates.unset("claimed_at"),
                Updates.unset("claim_token"),
                Updates.unset("claim_expires_at"),
                Updates.unset("started_at")
        )).getModifiedCount();

    }

    private Bson ownedClaim(final ObjectId id, final String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException("A job claim token is required.");
        }
        return Filters.and(Filters.eq("_id", id),
                Filters.eq("status", PendingDocumentEntity.STATUS_PROCESSING),
                Filters.eq("claim_token", claimToken));
    }

    private Bson liveComputation(final ObjectId id, final String claimToken) {
        return Filters.and(ownedClaim(id, claimToken),
                Filters.gt("claim_expires_at", new Date(clock.getAsLong())),
                Filters.exists("publication_started_at", false));
    }

    public boolean renewClaim(final ObjectId id, final String claimToken) {
        return jobs.updateOne(liveComputation(id, claimToken),
                Updates.set("claim_expires_at", new Date(clock.getAsLong() + claimLeaseMillis)))
                .getMatchedCount() == 1;
    }

    /**
     * Fences publication before evidence, metrics, or completion audit events are written.
     * This phase never expires: reclaiming it could race an already-issued external write.
     * An interrupted publisher requires operator recovery after the old worker is stopped.
     */
    public boolean beginPublication(final ObjectId id, final String claimToken) {
        return jobs.updateOne(liveComputation(id, claimToken),
                Updates.set("publication_started_at", new Date(clock.getAsLong())))
                .getModifiedCount() == 1;
    }

    public boolean markComplete(final ObjectId id, final ObjectId userId, final String claimToken, final byte[] output) {

        // Encrypted here as well as in the entity: this is a partial update, so it never passes
        // through toDocument and would otherwise write the redacted document in the clear.
        final EncryptedBytes encrypted = encryptionService.encryptBytes(output, userId.toHexString());

        final Bson query = Filters.and(ownedClaim(id, claimToken), Filters.eq("user_id", userId),
                Filters.exists("publication_started_at", true));
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_COMPLETE),
                Updates.set("output", new Binary(encrypted.ciphertext())),
                Updates.set("output_encrypted_key", encrypted.encryptionKey()),
                Updates.set("completed_at", new Date()),
                Updates.set("notification_pending", true),
                Updates.unset("input"),
                Updates.unset("input_encrypted_key")
        );

        return jobs.updateOne(query, update).getModifiedCount() == 1;

    }

    public boolean markFailed(final ObjectId id, final String claimToken, final String errorMessage) {

        final Bson query = Filters.and(ownedClaim(id, claimToken), Filters.or(
                Filters.exists("publication_started_at", true),
                Filters.gt("claim_expires_at", new Date(clock.getAsLong()))));
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_FAILED),
                Updates.set("error_message", errorMessage),
                Updates.set("completed_at", new Date()),
                Updates.set("notification_pending", true),
                Updates.unset("input"),
                Updates.unset("input_encrypted_key")
        );

        return jobs.updateOne(query, update).getModifiedCount() == 1;

    }

    public long deleteByDocumentIdAndUserId(final String documentId, final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("document_id", documentId),
                Filters.eq("user_id", userId),
                Filters.ne("notification_pending", true)
        );

        final DeleteResult result = jobs.deleteMany(query);
        if (result.getDeletedCount() == 0 && jobs.countDocuments(Filters.and(Filters.eq("document_id", documentId),
                Filters.eq("user_id", userId), Filters.eq("notification_pending", true))) > 0) {
            throw new QueueCapacityException("Notification dispatch is pending. Retry deletion later.", 409);
        }
        return result.getDeletedCount();

    }

    public int countByUserId(final ObjectId userId) {
        return (int) jobs.countDocuments(Filters.eq("user_id", userId));
    }

    public int countPendingByUserId(final ObjectId userId) {
        return (int) jobs.countDocuments(Filters.and(
                Filters.eq("user_id", userId),
                Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING)
        ));
    }

    public boolean hasOpenJobsForContext(final ObjectId userId, final String contextName) {
        return jobs.countDocuments(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("context_name", contextName),
                Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING)
        )) > 0;
    }

    public List<PendingDocumentEntity> pendingNotifications(final int limit) {
        final List<PendingDocumentEntity> result = new ArrayList<>();
        for (Document document : jobs.find(Filters.eq("notification_pending", true))
                .projection(com.mongodb.client.model.Projections.exclude("input", "output"))
                .sort(Sorts.ascending("notification_attempted_at", "completed_at")).limit(limit)) {
            result.add(PendingDocumentEntity.fromDocument(document, encryptionService));
        }
        return result;
    }

    public void recordNotificationAttempt(final ObjectId jobId) {
        jobs.updateOne(Filters.and(Filters.eq("_id", jobId), Filters.eq("notification_pending", true)),
                Updates.set("notification_attempted_at", new Date(clock.getAsLong())));
    }

    public void acknowledgeNotification(final ObjectId jobId) {
        jobs.updateOne(Filters.and(Filters.eq("_id", jobId), Filters.eq("notification_pending", true)),
                Updates.combine(Updates.set("notification_pending", false),
                        Updates.set("retention_at", new Date(clock.getAsLong()))));
    }
}
