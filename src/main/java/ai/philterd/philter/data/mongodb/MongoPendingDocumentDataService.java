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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredPendingDocumentDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Stores the asynchronous queue in MongoDB, which removes finished documents after their retention period. */
public class MongoPendingDocumentDataService extends StoredPendingDocumentDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoPendingDocumentDataService.class);

    /** Leave space below MongoDB's 16 MiB document limit for claim/publication metadata. */
    static final int MAX_ADMISSION_BSON_BYTES = 16 * 1024 * 1024 - 16 * 1024;

    private final MongoCollection<Document> jobs;
    private final MongoCollection<Document> snapshots;
    private final MongoCollection<Document> scheduling;

    public MongoPendingDocumentDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                      final AuditEventPublisher auditEventPublisher) {
        this(mongoClient, encryptionService, auditEventPublisher, System::currentTimeMillis);
    }

    MongoPendingDocumentDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                               final AuditEventPublisher auditEventPublisher, final LongSupplier clock) {
        this(mongoClient, encryptionService, auditEventPublisher, clock, claimLeaseSeconds());
    }

    /**
     * The lease is a parameter so a test can use a few seconds and watch a real takeover, which the
     * environment variable exists to make possible for a deployment too.
     */
    MongoPendingDocumentDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                               final AuditEventPublisher auditEventPublisher, final LongSupplier clock,
                               final int claimLeaseSeconds) {
        super(encryptionService, clock, claimLeaseSeconds, new QueueAdmission(mongoClient));
        final MongoRecords<PendingDocumentEntity> records = new MongoRecords<>(mongoClient, "pending_documents",
                entity -> PendingDocumentDocuments.toDocument(entity, encryptionService));
        snapshots = mongoClient.getDatabase("philter").getCollection("execution_snapshots");
        scheduling = mongoClient.getDatabase("philter").getCollection("queue_scheduling");
        jobs = records.collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);

        final long ttlSeconds = retentionSeconds();
        records.ensureIndex(Indexes.ascending("retention_at"), new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));
        RequiredSchema.rejectUnexpectedExpiry(records.collection, "retention_at");
        LOGGER.info("TTL index on pending_documents.retention_at set to expire after {} seconds.", ttlSeconds);

        // Per-document lookup/delete, the worker's claim scan, and per-user/context counts.
        records.ensureIndex(Indexes.ascending("user_id", "document_id"));
        records.ensureIndex(Indexes.ascending("status", "submitted_at"));
        records.ensureIndex(Indexes.ascending("status", "claim_expires_at"));
        records.ensureIndex(Indexes.ascending("user_id", "context_name", "status"));
    }

    @Override
    protected void requireStorable(final SealedJob job) {
        final Document document = PendingDocumentDocuments.toDocument(job);
        // Include the fields added at admission.
        if (!document.containsKey("_id")) document.put("_id", new ObjectId());
        document.put("fairness_round", 0L);
        requireStorable(document);
    }

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

    @Override
    protected void retainSnapshot(final SealedJob job) {
        // The same ciphertext the job stores: the configuration is encrypted once.
        final PendingDocumentEntity entity = job.entity();
        final SecretFields.Sealed sealed = job.effective();
        final Document retained = new Document("_id", entity.getUserId() + ":" + entity.getEffectiveHash())
                .append("user_id", MongoIds.objectId(entity.getUserId())).append("effective_hash", entity.getEffectiveHash())
                .append("effective_json", sealed.ciphertext())
                .append("effective_key", sealed.key());
        requireStorable(retained);
        snapshots.withWriteConcern(WriteConcern.MAJORITY).updateOne(Filters.eq("_id", retained.getString("_id")),
                new Document("$setOnInsert", retained), new UpdateOptions().upsert(true));
    }

    @Override
    protected long globalRound() {
        final Document round = scheduling.find(Filters.eq("_id", "global")).first();
        return round == null ? 0 : round.getLong("round");
    }

    @Override
    protected long ownerRound(final String userId) {
        final Document round = scheduling.find(Filters.eq("_id", MongoIds.objectId(userId))).first();
        return round == null ? 0 : round.getLong("round");
    }

    @Override
    protected void setOwnerRound(final String userId, final long round) {
        scheduling.updateOne(Filters.eq("_id", MongoIds.objectId(userId)), Updates.set("round", round),
                new UpdateOptions().upsert(true));
    }

    @Override
    protected String insert(final SealedJob job, final long round) {
        final Document document = PendingDocumentDocuments.toDocument(job);
        document.put("fairness_round", round);
        return MongoIds.hex(jobs.insertOne(document).getInsertedId().asObjectId().getValue());
    }

    @Override
    public QueueStats queueStats(final String owner) {
        Bson query = Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING);
        if (owner != null) query = Filters.and(query, Filters.eq("user_id", MongoIds.objectId(owner)));
        long count = 0, bytes = 0, oldest = clock.getAsLong();
        for (Document job : jobs.find(query).projection(Projections.include("input_size", "submitted_at"))) {
            count++;
            bytes += ((Number) job.getOrDefault("input_size", 0L)).longValue();
            if (job.getDate("submitted_at") != null) oldest = Math.min(oldest, job.getDate("submitted_at").getTime());
        }
        return new QueueStats(count, bytes, Math.max(0, (clock.getAsLong() - oldest) / 1000));
    }

    @Override
    public PendingDocumentEntity findOneByDocumentIdAndUserId(final String documentId, final String userId) {
        final Document document = jobs.find(Filters.and(
                Filters.eq("document_id", documentId),
                Filters.eq("user_id", MongoIds.objectId(userId)))).first();
        return document != null ? PendingDocumentDocuments.fromDocument(document, encryptionService) : null;
    }

    @Override
    public Listings.Page<PendingDocumentEntity> list(final String userId, final String status, final Listings.Sort sort,
                                                     final int offset, final int limit) {
        final Bson filter = MongoListings.all(Filters.eq("user_id", MongoIds.objectId(userId)), status == null ? null : Filters.eq("status", status));
        final List<PendingDocumentEntity> items = new ArrayList<>();
        for (final Document document : jobs.find(filter)
                .projection(Projections.exclude("input", "input_encrypted_key", "output",
                        "output_encrypted_key", "effective_json", "effective_key"))
                .sort(MongoListings.sort(sort)).skip(offset).limit(limit)) {
            items.add(PendingDocumentDocuments.fromDocument(document, encryptionService));
        }
        return new Listings.Page<>(items, jobs.countDocuments(filter));
    }

    @Override
    protected PendingDocumentEntity claim(final String workerId, final String claimToken, final Date now,
                                          final Date claimExpiresAt) {

        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_PROCESSING),
                Updates.set("claimed_by", workerId),
                Updates.set("claimed_at", now),
                Updates.set("claim_token", claimToken),
                Updates.set("claim_expires_at", claimExpiresAt),
                Updates.unset("publication_started_at"),
                Updates.set("started_at", now)
        );

        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .sort(Sorts.ascending("fairness_round", "submitted_at"))
                .returnDocument(ReturnDocument.AFTER);

        final Document claimed = jobs.findOneAndUpdate(Filters.eq("status", PendingDocumentEntity.STATUS_PENDING), update, options);
        if (claimed != null && claimed.getLong("fairness_round") != null) {
            scheduling.updateOne(Filters.eq("_id", "global"), Updates.max("round", claimed.getLong("fairness_round")),
                    new UpdateOptions().upsert(true));
        }
        return claimed != null ? PendingDocumentDocuments.fromDocument(claimed, encryptionService) : null;

    }

    @Override
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

    private static Bson ownedClaim(final String id, final String claimToken) {
        return Filters.and(Filters.eq("_id", MongoIds.objectId(id)),
                Filters.eq("status", PendingDocumentEntity.STATUS_PROCESSING),
                Filters.eq("claim_token", claimToken));
    }

    private static Bson liveComputation(final String id, final String claimToken, final Date now) {
        return Filters.and(ownedClaim(id, claimToken),
                Filters.gt("claim_expires_at", now),
                Filters.exists("publication_started_at", false));
    }

    @Override
    protected boolean renew(final String id, final String claimToken, final Date now, final Date claimExpiresAt) {
        return jobs.updateOne(liveComputation(id, claimToken, now), Updates.set("claim_expires_at", claimExpiresAt))
                .getMatchedCount() == 1;
    }

    @Override
    protected boolean begin(final String id, final String claimToken, final Date now) {
        return jobs.updateOne(liveComputation(id, claimToken, now), Updates.set("publication_started_at", now))
                .getModifiedCount() == 1;
    }

    @Override
    protected boolean complete(final String id, final String userId, final String claimToken,
                               final SecretFields.SealedBytes output, final Date now) {
        final Bson query = Filters.and(ownedClaim(id, claimToken), Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.exists("publication_started_at", true));
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_COMPLETE),
                Updates.set("output", new Binary(output.ciphertext())),
                Updates.set("output_encrypted_key", output.key()),
                Updates.set("completed_at", now),
                Updates.set("notification_pending", true),
                Updates.unset("input"),
                Updates.unset("input_encrypted_key")
        );
        return jobs.updateOne(query, update).getModifiedCount() == 1;
    }

    @Override
    protected boolean fail(final String id, final String claimToken, final String errorMessage, final Date now) {
        final Bson query = Filters.and(ownedClaim(id, claimToken), Filters.or(
                Filters.exists("publication_started_at", true),
                Filters.gt("claim_expires_at", new Date(clock.getAsLong()))));
        final Bson update = Updates.combine(
                Updates.set("status", PendingDocumentEntity.STATUS_FAILED),
                Updates.set("error_message", errorMessage),
                Updates.set("completed_at", now),
                Updates.set("notification_pending", true),
                Updates.unset("input"),
                Updates.unset("input_encrypted_key")
        );
        return jobs.updateOne(query, update).getModifiedCount() == 1;
    }

    private static Bson owned(final String documentId, final String userId) {
        return Filters.and(Filters.eq("document_id", documentId), Filters.eq("user_id", MongoIds.objectId(userId)));
    }

    @Override
    protected long deleteNotified(final String documentId, final String userId) {
        return jobs.deleteMany(Filters.and(owned(documentId, userId), Filters.ne("notification_pending", true))).getDeletedCount();
    }

    @Override
    protected boolean hasPendingNotification(final String documentId, final String userId) {
        return jobs.countDocuments(Filters.and(owned(documentId, userId), Filters.eq("notification_pending", true))) > 0;
    }

    @Override
    public boolean hasOpenJobsForContext(final String userId, final String contextName) {
        return jobs.countDocuments(Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("context_name", contextName),
                Filters.in("status", PendingDocumentEntity.STATUS_PENDING, PendingDocumentEntity.STATUS_PROCESSING)
        )) > 0;
    }

    @Override
    public List<PendingDocumentEntity> pendingNotifications(final int limit) {
        final List<PendingDocumentEntity> result = new ArrayList<>();
        for (Document document : jobs.find(Filters.eq("notification_pending", true))
                .projection(Projections.exclude("input", "output"))
                .sort(Sorts.ascending("notification_attempted_at", "completed_at")).limit(limit)) {
            result.add(PendingDocumentDocuments.fromDocument(document, encryptionService));
        }
        return result;
    }

    @Override
    public void recordNotificationAttempt(final String jobId) {
        jobs.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(jobId)), Filters.eq("notification_pending", true)),
                Updates.set("notification_attempted_at", new Date(clock.getAsLong())));
    }

    @Override
    public void acknowledgeNotification(final String jobId) {
        jobs.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(jobId)), Filters.eq("notification_pending", true)),
                Updates.combine(Updates.set("notification_pending", false),
                        Updates.set("retention_at", new Date(clock.getAsLong()))));
    }

}
