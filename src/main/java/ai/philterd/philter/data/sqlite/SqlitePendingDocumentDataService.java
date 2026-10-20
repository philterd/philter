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
package ai.philterd.philter.data.sqlite;

import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.FairnessKeys;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredPendingDocumentDataService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Stores the asynchronous queue in SQLite, removing finished documents after their retention period, both as documents
 * are submitted and on {@link SqliteRetention}'s schedule.
 */
public class SqlitePendingDocumentDataService extends StoredPendingDocumentDataService implements SqliteRetention.Expiring {

    private static final String OPEN = "status IN ('" + PendingDocumentEntity.STATUS_PENDING + "', '"
            + PendingDocumentEntity.STATUS_PROCESSING + "')";

    private final SqliteDatabase database;

    public SqlitePendingDocumentDataService(final SqliteDatabase database, final EncryptionService encryptionService) {
        this(database, encryptionService, System::currentTimeMillis, claimLeaseSeconds());
    }

    /** Reading the time from {@code clock}, for tests. */
    SqlitePendingDocumentDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                     final LongSupplier clock) {
        this(database, encryptionService, clock, claimLeaseSeconds());
    }

    SqlitePendingDocumentDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                     final LongSupplier clock, final int claimLeaseSeconds) {
        super(encryptionService, clock, claimLeaseSeconds, new SqliteAdmissionGuard(database));
        this.database = database;
    }

    private PendingDocumentEntity read(final Sql.Row row, final boolean withContent) throws SQLException {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setId(row.string("id"));
        entity.setUserId(row.string("user_id"));
        entity.setDocumentId(row.string("document_id"));
        entity.setFileName(row.string("file_name"));
        entity.setInputMimeType(row.string("input_mime_type"));
        entity.setOutputMimeType(row.string("output_mime_type"));
        entity.setPolicyName(row.string("policy_name"));
        final Integer version = row.integer("policy_version");
        entity.setPolicyVersion(version == null ? -1 : version);
        entity.setPolicyContentHash(row.string("policy_content_hash"));
        entity.setContextName(row.string("context_name"));
        entity.setFairnessKey(row.string("fairness_key"));
        entity.setClientIpAddress(row.string("client_ip_address"));
        entity.setEffectiveHash(row.string("effective_hash"));
        entity.setStatus(row.string("status"));
        entity.setErrorMessage(row.string("error_message"));
        if (withContent) {
            entity.setEffectiveJson(SecretFields.open(encryptionService, row.string("effective_json"), row.string("effective_key")));
            entity.setInput(SecretFields.openBytes(encryptionService, row.bytes("input"), row.string("input_encrypted_key")));
            entity.setOutput(SecretFields.openBytes(encryptionService, row.bytes("output"), row.string("output_encrypted_key")));
        }
        entity.setSubmittedAt(row.date("submitted_at"));
        entity.setStartedAt(row.date("started_at"));
        entity.setCompletedAt(row.date("completed_at"));
        entity.setClaimedBy(row.string("claimed_by"));
        entity.setClaimedAt(row.date("claimed_at"));
        entity.setClaimToken(row.string("claim_token"));
        entity.setClaimExpiresAt(row.date("claim_expires_at"));
        entity.setReclaimCount(row.intValue("reclaim_count"));
        return entity;
    }

    @Override
    public void removeExpired() {
        database.write(connection -> {
            prune(connection);
            return null;
        });
    }

    private void prune(final Connection connection) throws SQLException {
        Sql.update(connection, "DELETE FROM pending_documents WHERE retention_at <= ?",
                new Date(clock.getAsLong() - retentionSeconds() * 1000L));
    }

    @Override
    protected void requireStorable(final SealedJob job) {
        // SQLite's own limit on a row is far above the request size limits, so nothing is refused here.
    }

    private long round(final String owner) {
        final Long round = database.read(connection -> Sql.first(connection,
                "SELECT round FROM queue_scheduling WHERE owner = ?", row -> row.longValue("round"), owner));
        return round == null ? 0 : round;
    }

    @Override
    protected long globalRound() {
        return round("global");
    }

    @Override
    protected long ownerRound(final String userId) {
        return round(userId);
    }

    @Override
    protected void setOwnerRound(final String userId, final long round) {
        database.write(connection -> Sql.update(connection, "INSERT INTO queue_scheduling (owner, round) VALUES (?, ?) "
                + "ON CONFLICT (owner) DO UPDATE SET round = excluded.round", userId, round));
    }

    @Override
    protected String insert(final SealedJob job, final Function<List<FairnessKeys.Queued>, FairnessKeys.Plan> planner) {
        final PendingDocumentEntity entity = job.entity();
        final String id = entity.getId() != null ? entity.getId() : ai.philterd.philter.data.RecordIds.newId();
        final SecretFields.Sealed effective = job.effective();
        final SecretFields.SealedBytes input = job.input();
        final SecretFields.SealedBytes output = job.output();
        database.write(connection -> {
            prune(connection);
            // In the same write as the claims, so no document is claimed while its round is moving.
            final FairnessKeys.Plan plan = planner.apply(Sql.query(connection, "SELECT id, fairness_key, fairness_round "
                    + "FROM pending_documents WHERE user_id = ? AND status = ? ORDER BY fairness_round ASC, submitted_at ASC, "
                    + "seq ASC", row -> new FairnessKeys.Queued(row.string("id"), row.string("fairness_key"),
                    row.longValue("fairness_round")), entity.getUserId(), PendingDocumentEntity.STATUS_PENDING));
            for (final Map.Entry<String, Long> moved : plan.reassigned().entrySet()) {
                Sql.update(connection, "UPDATE pending_documents SET fairness_round = ? WHERE id = ?",
                        moved.getValue(), moved.getKey());
            }
            return Sql.update(connection, "INSERT INTO pending_documents (id, user_id, document_id, file_name, input_mime_type, "
                    + "output_mime_type, policy_name, policy_version, policy_content_hash, context_name, client_ip_address, "
                    + "input_size, effective_hash, effective_json, effective_key, status, error_message, input, "
                    + "input_encrypted_key, output, output_encrypted_key, submitted_at, started_at, completed_at, claimed_by, "
                    + "claimed_at, claim_token, claim_expires_at, reclaim_count, fairness_round, fairness_key) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, entity.getUserId(), entity.getDocumentId(), entity.getFileName(), entity.getInputMimeType(),
                    entity.getOutputMimeType(), entity.getPolicyName(), entity.getPolicyVersion(), entity.getPolicyContentHash(),
                    entity.getContextName(), entity.getClientIpAddress(),
                    entity.getInput() == null ? 0L : (long) entity.getInput().length, entity.getEffectiveHash(),
                    effective == null ? null : effective.ciphertext(), effective == null ? null : effective.key(),
                    entity.getStatus(), entity.getErrorMessage(), input == null ? null : input.ciphertext(),
                    input == null ? null : input.key(), output == null ? null : output.ciphertext(),
                    output == null ? null : output.key(), entity.getSubmittedAt(), entity.getStartedAt(),
                    entity.getCompletedAt(), entity.getClaimedBy(), entity.getClaimedAt(), entity.getClaimToken(),
                    entity.getClaimExpiresAt(), entity.getReclaimCount(), plan.round(), entity.getFairnessKey());
        });
        return id;
    }

    @Override
    public QueueStats queueStats(final String owner) {
        final long now = clock.getAsLong();
        return database.read(connection -> {
            final String where = " WHERE " + OPEN + (owner == null ? "" : " AND user_id = ?");
            final Object[] params = owner == null ? new Object[0] : new Object[]{owner};
            return Sql.first(connection, "SELECT COUNT(*) AS jobs, ifnull(SUM(input_size), 0) AS bytes, "
                    + "MIN(submitted_at) AS oldest FROM pending_documents" + where, row -> {
                        final Date oldest = row.date("oldest");
                        return new QueueStats(row.longValue("jobs"), row.longValue("bytes"),
                                oldest == null ? 0 : Math.max(0, (now - Math.min(now, oldest.getTime())) / 1000));
                    }, params);
        });
    }

    @Override
    public PendingDocumentEntity findOneByDocumentIdAndUserId(final String documentId, final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM pending_documents WHERE document_id = ? "
                + "AND user_id = ?", row -> read(row, true), documentId, userId));
    }

    @Override
    public Listings.Page<PendingDocumentEntity> list(final String userId, final String status, final String q,
                                                     final Listings.Sort sort, final int offset, final int limit) {
        final List<Object> params = new ArrayList<>(List.of(userId));
        String state = null;
        if (status != null) {
            state = "status = ?";
            params.add(status);
        }
        String search = null;
        if (q != null && !q.isBlank()) {
            final List<Object> searchParams = new ArrayList<>();
            search = Sql.contains("document_id", q, searchParams) + " OR " + Sql.contains("file_name", q, searchParams);
            params.addAll(searchParams);
        }
        final String where = Sql.where("user_id = ?", state, search);
        return database.read(connection -> Sql.page(connection, "pending_documents", where, params, sort, offset, limit,
                row -> read(row, false)));
    }

    @Override
    protected PendingDocumentEntity claim(final String workerId, final String claimToken, final Date now,
                                          final Date claimExpiresAt) {
        return database.write(connection -> {
            final Object[] next = Sql.first(connection, "SELECT id, fairness_round FROM pending_documents WHERE status = ? "
                    + "ORDER BY fairness_round ASC, submitted_at ASC, seq ASC LIMIT 1",
                    row -> new Object[]{row.string("id"), row.longValue("fairness_round")}, PendingDocumentEntity.STATUS_PENDING);
            if (next == null) {
                return null;
            }
            Sql.update(connection, "UPDATE pending_documents SET status = ?, claimed_by = ?, claimed_at = ?, claim_token = ?, "
                    + "claim_expires_at = ?, publication_started_at = NULL, started_at = ? WHERE id = ?",
                    PendingDocumentEntity.STATUS_PROCESSING, workerId, now, claimToken, claimExpiresAt, now, next[0]);
            Sql.update(connection, "INSERT INTO queue_scheduling (owner, round) VALUES ('global', ?) "
                    + "ON CONFLICT (owner) DO UPDATE SET round = max(round, excluded.round)", next[1]);
            return Sql.first(connection, "SELECT * FROM pending_documents WHERE id = ?", row -> read(row, true), next[0]);
        });
    }

    @Override
    public long reclaimStuckJobs(final Date now, final int maxReclaims) {
        return database.write(connection -> {
            final String stuck = "status = '" + PendingDocumentEntity.STATUS_PROCESSING
                    + "' AND claim_expires_at <= ? AND publication_started_at IS NULL";
            // Past the cap the job is failed with durable notification intent. Acknowledgement starts retention.
            Sql.update(connection, "UPDATE pending_documents SET status = ?, error_message = ?, completed_at = ?, "
                    + "notification_pending = 1, input = NULL, input_encrypted_key = NULL, claimed_by = NULL, claimed_at = NULL, "
                    + "claim_token = NULL, claim_expires_at = NULL WHERE " + stuck + " AND reclaim_count >= ?",
                    PendingDocumentEntity.STATUS_FAILED, "Abandoned after " + maxReclaims
                            + " attempts; the worker did not complete this document.", new Date(), now, maxReclaims);
            return (long) Sql.update(connection, "UPDATE pending_documents SET status = ?, reclaim_count = reclaim_count + 1, "
                    + "claimed_by = NULL, claimed_at = NULL, claim_token = NULL, claim_expires_at = NULL, started_at = NULL "
                    + "WHERE " + stuck + " AND reclaim_count < ?", PendingDocumentEntity.STATUS_PENDING, now, maxReclaims);
        });
    }

    private static final String OWNED = "id = ? AND status = '" + PendingDocumentEntity.STATUS_PROCESSING + "' AND claim_token = ?";

    @Override
    protected boolean renew(final String id, final String claimToken, final Date now, final Date claimExpiresAt) {
        return database.write(connection -> Sql.update(connection, "UPDATE pending_documents SET claim_expires_at = ? WHERE "
                + OWNED + " AND claim_expires_at > ? AND publication_started_at IS NULL", claimExpiresAt, id, claimToken, now)) == 1;
    }

    @Override
    protected boolean begin(final String id, final String claimToken, final Date now) {
        return database.write(connection -> Sql.update(connection, "UPDATE pending_documents SET publication_started_at = ? "
                + "WHERE " + OWNED + " AND claim_expires_at > ? AND publication_started_at IS NULL", now, id, claimToken, now)) == 1;
    }

    @Override
    protected boolean complete(final String id, final String userId, final String claimToken,
                               final SecretFields.SealedBytes output, final Date now) {
        return database.write(connection -> Sql.update(connection, "UPDATE pending_documents SET status = ?, output = ?, "
                + "output_encrypted_key = ?, completed_at = ?, notification_pending = 1, input = NULL, input_encrypted_key = NULL "
                + "WHERE " + OWNED + " AND user_id = ? AND publication_started_at IS NOT NULL",
                PendingDocumentEntity.STATUS_COMPLETE, output == null ? null : output.ciphertext(),
                output == null ? null : output.key(), now, id, claimToken, userId)) == 1;
    }

    @Override
    protected boolean fail(final String id, final String claimToken, final String errorMessage, final Date now) {
        return database.write(connection -> Sql.update(connection, "UPDATE pending_documents SET status = ?, error_message = ?, "
                + "completed_at = ?, notification_pending = 1, input = NULL, input_encrypted_key = NULL WHERE " + OWNED
                + " AND (publication_started_at IS NOT NULL OR claim_expires_at > ?)",
                PendingDocumentEntity.STATUS_FAILED, errorMessage, now, id, claimToken, new Date(clock.getAsLong()))) == 1;
    }

    @Override
    protected long deleteNotified(final String documentId, final String userId) {
        return database.write(connection -> (long) Sql.update(connection, "DELETE FROM pending_documents WHERE document_id = ? "
                + "AND user_id = ? AND notification_pending = 0", documentId, userId));
    }

    @Override
    protected boolean hasPendingNotification(final String documentId, final String userId) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM pending_documents WHERE "
                + "document_id = ? AND user_id = ? AND notification_pending = 1", documentId, userId) > 0);
    }

    @Override
    public boolean hasOpenJobsForContext(final String userId, final String contextName) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM pending_documents WHERE "
                + "user_id = ? AND context_name = ? AND " + OPEN, userId, contextName) > 0);
    }

    @Override
    public List<PendingDocumentEntity> pendingNotifications(final int limit) {
        return database.read(connection -> Sql.query(connection, "SELECT * FROM pending_documents WHERE notification_pending = 1 "
                + "ORDER BY notification_attempted_at ASC, completed_at ASC, seq ASC LIMIT ?", row -> read(row, false), limit));
    }

    @Override
    public void recordNotificationAttempt(final String jobId) {
        database.write(connection -> Sql.update(connection, "UPDATE pending_documents SET notification_attempted_at = ? "
                + "WHERE id = ? AND notification_pending = 1", new Date(clock.getAsLong()), jobId));
    }

    @Override
    public void acknowledgeNotification(final String jobId) {
        database.write(connection -> Sql.update(connection, "UPDATE pending_documents SET notification_pending = 0, "
                + "retention_at = ? WHERE id = ? AND notification_pending = 1", new Date(clock.getAsLong()), jobId));
    }

}
