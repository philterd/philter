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
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.SigningKeyEntity;
import ai.philterd.philter.data.schema.SchemaMigrator;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Writes the records of another storage into an empty MongoDB database as MongoDB storage stores them, for a
 * migration to MongoDB. Each record arrives as a row of plain values ({@code Long}, {@code Double}, {@code String},
 * {@code byte[]}, or null) named as SQLite storage names its columns, and is written as the document the MongoDB
 * services would have written: the same ids, field names, and types. Encrypted fields are copied as they are, never
 * decrypted, since both storages encrypt the same way.
 *
 * <p>Afterwards it verifies the copy through the MongoDB services: ledger chains, signing keys, and encrypted fields.
 */
public final class MongoImport implements AutoCloseable {

    /** How many documents are inserted at once, at most. */
    private static final int BATCH = 500;

    /** How many bytes of documents are held for one insert, at most: asynchronous documents can be large. */
    static final long BATCH_BYTES = 16L * 1024 * 1024;

    /** How many records of each kind of encrypted field are decrypted to verify the copy. */
    private static final int SAMPLE = 5;

    private final MongoClient client;
    private final boolean ownsClient;
    private final MongoDatabase database;
    private final EncryptionService encryptionService;

    private MongoImport(final MongoClient client, final boolean ownsClient, final EncryptionService encryptionService) {
        this.client = client;
        this.ownsClient = ownsClient;
        this.database = client.getDatabase("philter");
        this.encryptionService = encryptionService;
    }

    /** Over {@code client}, which the caller closes. */
    MongoImport(final MongoClient client, final EncryptionService encryptionService) {
        this(client, false, encryptionService);
    }

    /** Connects to the MongoDB at {@code connectionString}. Nothing is written until records are copied. */
    public static MongoImport connect(final String connectionString, final EncryptionService encryptionService) {
        return new MongoImport(MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString)).build()), true, encryptionService);
    }

    /** The schema version this Philter's MongoDB storage expects. */
    public int expectedSchemaVersion() {
        return SchemaMigrator.expectedVersion(MongoMigrations.all(client));
    }

    /** Whether the database holds any Philter data, or a schema version. */
    public boolean holdsPhilterData() {
        final MongoSchemaStore schema = new MongoSchemaStore(client);
        return schema.currentVersion() != null || schema.hasData();
    }

    /** Records the schema version the copied data is at. */
    public void recordSchemaVersion(final int version, final Instant at) {
        new MongoSchemaStore(client).initialize(version, at);
    }

    // Values as they arrive, converted to the types MongoDB storage writes.

    private static String text(final Map<String, Object> row, final String column) {
        return (String) row.get(column);
    }

    /** A record id, or null; an empty id, which SQLite storage keeps for managed policies, is none. */
    private static ObjectId id(final Map<String, Object> row, final String column) {
        final String id = (String) row.get(column);
        return id == null || id.isEmpty() ? null : new ObjectId(id);
    }

    /** A record id where one is expected, kept as text when it is not one, as an audit event may hold. */
    private static Object idOrText(final Map<String, Object> row, final String column) {
        final String value = (String) row.get(column);
        return RecordIds.isValid(value) ? new ObjectId(value) : value;
    }

    private static Date date(final Map<String, Object> row, final String column) {
        final Long millis = (Long) row.get(column);
        return millis == null ? null : new Date(millis);
    }

    private static boolean bool(final Map<String, Object> row, final String column) {
        final Long value = (Long) row.get(column);
        return value != null && value != 0;
    }

    private static Integer int32(final Map<String, Object> row, final String column) {
        final Long value = (Long) row.get(column);
        return value == null ? null : Math.toIntExact(value);
    }

    private static long int64(final Map<String, Object> row, final String column) {
        final Long value = (Long) row.get(column);
        return value == null ? 0L : value;
    }

    private static Binary binary(final Map<String, Object> row, final String column) {
        final byte[] value = (byte[]) row.get(column);
        return value == null ? null : new Binary(value);
    }

    /** Puts the field only when it has a value: fields MongoDB storage leaves out, rather than sets to null. */
    private static void putIfPresent(final Document document, final String field, final Object value) {
        if (value != null) {
            document.put(field, value);
        }
    }

    /** Where each SQLite table's records go in MongoDB, and how each becomes a document. */
    private record Target(String collection, Function<Map<String, Object>, Document> toDocument) {
    }

    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("admin_settings", new Target("admin_settings", row -> new Document("_id", new ObjectId())
                .append("diffuse_counts_enabled", bool(row, "diffuse_counts_enabled"))
                .append("signing_enabled", bool(row, "signing_enabled"))
                .append("webhook_allowlist", text(row, "webhook_allowlist"))
                .append("phield_enabled", bool(row, "phield_enabled"))
                .append("phield_url", text(row, "phield_url"))
                .append("phield_source_id", text(row, "phield_source_id"))
                .append("phield_organization", text(row, "phield_organization"))
                .append("phield_api_key", text(row, "phield_api_key"))
                .append("phield_api_key_key", text(row, "phield_api_key_key"))
                .append("mfa_available", bool(row, "mfa_available"))
                .append("mfa_required", bool(row, "mfa_required"))));

        // MongoDB storage keeps no created_at for a user: it reads the time from the id, which is the same time.
        TARGETS.put("users", new Target("users", row -> new Document("_id", id(row, "id"))
                .append("username", text(row, "username"))
                .append("email", text(row, "email"))
                .append("role", text(row, "role"))
                .append("fpe_key", text(row, "fpe_key"))
                .append("fpe_key_key", text(row, "fpe_key_key"))
                .append("webhook_url", text(row, "webhook_url"))
                .append("webhook_secret", text(row, "webhook_secret"))
                .append("webhook_secret_key", text(row, "webhook_secret_key"))
                .append("password", text(row, "password"))
                .append("password_change_required", bool(row, "password_change_required"))
                .append("mfa_enabled", bool(row, "mfa_enabled"))
                .append("mfa_secret", text(row, "mfa_secret"))
                .append("mfa_secret_key", text(row, "mfa_secret_key"))
                .append("mfa_pending_secret", text(row, "mfa_pending_secret"))
                .append("mfa_pending_secret_key", text(row, "mfa_pending_secret_key"))
                .append("mfa_failed_attempts", int32(row, "mfa_failed_attempts"))
                .append("mfa_locked", bool(row, "mfa_locked"))
                .append("mfa_last_used_time_step", int64(row, "mfa_last_used_time_step"))
                .append("deactivated", bool(row, "deactivated"))
                .append("deactivated_at", date(row, "deactivated_at"))));

        TARGETS.put("api_keys", new Target("api_keys", row -> {
            final Document key = new Document("_id", id(row, "id"))
                    .append("user_id", id(row, "user_id"))
                    .append("api_key_hash", text(row, "api_key_hash"))
                    .append("api_key_prefix", text(row, "api_key_prefix"))
                    .append("deleted", bool(row, "deleted"))
                    .append("deleted_at", date(row, "deleted_at"))
                    .append("timestamp", date(row, "timestamp"))
                    .append("bootstrap", bool(row, "bootstrap"))
                    .append("session", bool(row, "session"));
            // Only session keys carry these, as MongoDB storage writes them.
            if (bool(row, "session")) {
                key.append("expires_at", date(row, "expires_at"))
                        .append("idle_timeout_seconds", int32(row, "idle_timeout_seconds"))
                        .append("idle_expires_at", date(row, "idle_expires_at"))
                        .append("last_used_at", date(row, "last_used_at"))
                        .append("password_change_only", bool(row, "password_change_only"))
                        .append("mfa_enrollment_only", bool(row, "mfa_enrollment_only"));
                putIfPresent(key, "client_address", text(row, "client_address"));
                putIfPresent(key, "user_agent", text(row, "user_agent"));
            }
            final String scopes = text(row, "scopes");
            key.append("scopes", scopes == null || scopes.isEmpty() ? List.of() : Arrays.asList(scopes.split(",")));
            return key;
        }));

        TARGETS.put("sign_in_challenges", new Target("sign_in_challenges", row -> new Document("_id", new ObjectId())
                .append("token_hash", text(row, "token_hash"))
                .append("user_id", id(row, "user_id"))
                .append("expires_at", date(row, "expires_at"))));

        TARGETS.put("signing_keys", new Target("signing_keys", row -> {
            final Document key = new Document("_id", new ObjectId()).append("key_id", text(row, "key_id"));
            // A key kept only to verify old signatures has no private half.
            putIfPresent(key, "private_key", binary(row, "private_key"));
            putIfPresent(key, "private_key_encrypted_key", text(row, "private_key_encrypted_key"));
            return key.append("public_key", binary(row, "public_key")).append("created_at", date(row, "created_at"));
        }));

        TARGETS.put("signing_key_state", new Target("signing_key_state", row -> new Document("_id", text(row, "name"))
                .append("key_id", text(row, "key_id"))));

        TARGETS.put("policies", new Target("policies", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("name", text(row, "name"))
                .append("policy", text(row, "policy"))
                .append("created_timestamp", date(row, "created_timestamp"))
                .append("revision", int32(row, "revision"))
                .append("last_updated_timestamp", date(row, "last_updated_timestamp"))
                .append("shared", bool(row, "shared"))
                .append("notes", text(row, "notes"))
                .append("description", text(row, "description"))
                .append("managed", bool(row, "managed"))));

        TARGETS.put("policy_revision_counters", new Target("policy_revision_counters", row -> new Document("_id",
                new Document("user_id", id(row, "user_id")).append("name", text(row, "name")))
                .append("next", int32(row, "next"))));

        TARGETS.put("policy_versions", new Target("policy_versions", row -> {
            final Document version = new Document("_id", id(row, "id"))
                    .append("user_id", id(row, "user_id"))
                    .append("name", text(row, "name"))
                    .append("revision", int32(row, "revision"))
                    .append("content_hash", text(row, "content_hash"))
                    .append("policy", text(row, "policy"))
                    .append("captured_timestamp", date(row, "captured_timestamp"));
            putIfPresent(version, "author_id", id(row, "author_id"));
            return version;
        }));

        TARGETS.put("policy_contents", new Target("policy_contents", row -> new Document("_id", text(row, "hash"))
                .append("policy", text(row, "policy"))));

        TARGETS.put("policy_deletions", new Target("policy_deletions", row -> new Document("_id", new ObjectId())
                .append("user_id", id(row, "user_id"))
                .append("name", text(row, "name"))
                .append("revision", int32(row, "revision"))
                .append("deleted_by", id(row, "deleted_by"))
                .append("deleted_at", date(row, "deleted_at"))));

        TARGETS.put("custom_lists", new Target("custom_lists", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("name", text(row, "name"))
                .append("description", text(row, "description"))
                .append("items", text(row, "items"))
                .append("items_encrypted_key", text(row, "items_encrypted_key"))));

        TARGETS.put("redact_lists", new Target("redact_lists", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("terms_to_always_redact", text(row, "terms_to_always_redact"))
                .append("terms_to_always_redact_key", text(row, "terms_to_always_redact_key"))
                .append("terms_to_never_redact", text(row, "terms_to_never_redact"))
                .append("terms_to_never_redact_key", text(row, "terms_to_never_redact_key"))
                .append("always_redact_revision", int64(row, "always_redact_revision"))
                .append("never_redact_revision", int64(row, "never_redact_revision"))));

        TARGETS.put("contexts", new Target("contexts", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("context_name", text(row, "context_name"))
                .append("max_size", int32(row, "max_size"))
                .append("disambiguation", bool(row, "disambiguation"))
                .append("disambiguation_scope", text(row, "disambiguation_scope"))
                .append("ledger", bool(row, "ledger"))
                .append("slot", int32(row, "slot"))
                .append("ttl_in_hours", int32(row, "ttl_in_hours"))
                .append("timestamp", date(row, "timestamp"))));

        TARGETS.put("context_entries", new Target("context_entries", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("context_name", text(row, "context_name"))
                .append("token_hash", text(row, "token_hash"))
                .append("replacement", text(row, "replacement"))
                .append("replacement_uuid", bool(row, "replacement_uuid"))
                .append("reads", int64(row, "reads"))
                .append("timestamp", date(row, "timestamp"))
                .append("filter_type", text(row, "filter_type"))));

        // Rows arrive in the order they were written, and new ids sort in the order they are made, so the oldest
        // vector, which is evicted first, stays the oldest.
        TARGETS.put("vectors", new Target("vectors", row -> new Document("_id", new ObjectId())
                .append("user_id", id(row, "user_id"))
                .append("context", text(row, "context"))
                .append("hash", ((Number) row.get("hash")).doubleValue())
                .append("vector_size", int32(row, "vector_size") == null ? 0 : int32(row, "vector_size"))
                .append("filter_type", text(row, "filter_type"))));

        TARGETS.put("legal_holds", new Target("legal_holds", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("reference", text(row, "reference"))
                .append("scope_type", text(row, "scope_type"))
                .append("scope_value", text(row, "scope_value"))
                .append("reason", text(row, "reason"))
                .append("set_at", date(row, "set_at"))
                .append("set_by_user_id", id(row, "set_by_user_id"))));

        TARGETS.put("ledger", new Target("ledger", row -> new Document("_id", id(row, "id"))
                .append("token", text(row, "token"))
                .append("token_encrypted_key", text(row, "token_encrypted_key"))
                .append("replacement", text(row, "replacement"))
                .append("replacement_encrypted_key", text(row, "replacement_encrypted_key"))
                .append("user_id", id(row, "user_id"))
                .append("document_id", text(row, "document_id"))
                .append("start_position", int64(row, "start_position"))
                .append("chain_position", int64(row, "chain_position"))
                .append("document_hash", text(row, "document_hash"))
                .append("previous_hash", text(row, "previous_hash"))
                .append("hash", text(row, "hash"))
                .append("timestamp", date(row, "timestamp"))
                .append("filename", text(row, "filename"))
                .append("type", text(row, "type"))
                .append("policy_name", text(row, "policy_name"))
                .append("policy_version", int32(row, "policy_version"))
                .append("policy_content_hash", text(row, "policy_content_hash"))
                .append("effective_hash", text(row, "effective_hash"))
                .append("signature", text(row, "signature"))
                .append("signing_key_id", text(row, "signing_key_id"))));

        TARGETS.put("ledger_chains", new Target("ledger_chains", row -> {
            final Document chain = new Document("_id", text(row, "user_id") + ":" + text(row, "document_id"))
                    .append("user_id", id(row, "user_id"))
                    .append("document_id", text(row, "document_id"))
                    .append("state", text(row, "state"));
            putIfPresent(chain, "completed_at", date(row, "completed_at"));
            putIfPresent(chain, "latest_entry_at", date(row, "latest_entry_at"));
            return chain;
        }));

        TARGETS.put("pending_documents", new Target("pending_documents", row -> {
            final Document job = new Document("_id", id(row, "id"))
                    .append("user_id", id(row, "user_id"))
                    .append("document_id", text(row, "document_id"))
                    .append("file_name", text(row, "file_name"))
                    .append("input_mime_type", text(row, "input_mime_type"))
                    .append("output_mime_type", text(row, "output_mime_type"))
                    .append("policy_name", text(row, "policy_name"))
                    .append("policy_version", int32(row, "policy_version"))
                    .append("policy_content_hash", text(row, "policy_content_hash"))
                    .append("context_name", text(row, "context_name"))
                    .append("client_ip_address", text(row, "client_ip_address"))
                    .append("input_size", int64(row, "input_size"))
                    .append("effective_hash", text(row, "effective_hash"));
            putIfPresent(job, "effective_json", text(row, "effective_json"));
            putIfPresent(job, "effective_key", text(row, "effective_key"));
            job.append("status", text(row, "status")).append("error_message", text(row, "error_message"));
            putIfPresent(job, "input", binary(row, "input"));
            putIfPresent(job, "input_encrypted_key", text(row, "input_encrypted_key"));
            putIfPresent(job, "output", binary(row, "output"));
            putIfPresent(job, "output_encrypted_key", text(row, "output_encrypted_key"));
            job.append("submitted_at", date(row, "submitted_at"))
                    .append("started_at", date(row, "started_at"))
                    .append("completed_at", date(row, "completed_at"))
                    .append("claimed_by", text(row, "claimed_by"))
                    .append("claimed_at", date(row, "claimed_at"))
                    .append("claim_token", text(row, "claim_token"))
                    .append("claim_expires_at", date(row, "claim_expires_at"))
                    .append("reclaim_count", int32(row, "reclaim_count"))
                    .append("fairness_round", int64(row, "fairness_round"))
                    .append("notification_pending", bool(row, "notification_pending"));
            // Left out, not null, when unset: claims and publication match on the field being absent.
            putIfPresent(job, "publication_started_at", date(row, "publication_started_at"));
            putIfPresent(job, "notification_attempted_at", date(row, "notification_attempted_at"));
            putIfPresent(job, "retention_at", date(row, "retention_at"));
            return job;
        }));

        TARGETS.put("queue_scheduling", new Target("queue_scheduling", row -> new Document("_id",
                "global".equals(text(row, "owner")) ? "global" : id(row, "owner"))
                .append("round", int64(row, "round"))));

        TARGETS.put("execution_snapshots", new Target("execution_snapshots", row -> new Document("_id", text(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("effective_hash", text(row, "effective_hash"))
                .append("effective_json", text(row, "effective_json"))
                .append("effective_key", text(row, "effective_key"))));

        TARGETS.put("webhook_deliveries", new Target("webhook_deliveries", row -> new Document("_id", id(row, "id"))
                .append("user_id", id(row, "user_id"))
                .append("document_id", text(row, "document_id"))
                .append("event_type", text(row, "event_type"))
                .append("status", text(row, "status"))
                .append("url", text(row, "url"))
                .append("secret", text(row, "secret"))
                .append("secret_encrypted_key", text(row, "secret_encrypted_key"))
                .append("payload", text(row, "payload"))
                .append("attempts", int32(row, "attempts"))
                .append("claim_token", text(row, "claim_token"))
                .append("claim_expires_at", date(row, "claim_expires_at"))
                .append("last_error", text(row, "last_error"))
                .append("next_attempt_at", date(row, "next_attempt_at"))
                .append("created_at", date(row, "created_at"))
                .append("updated_at", date(row, "updated_at"))
                .append("delivered_at", date(row, "delivered_at"))
                .append("completed_at", date(row, "completed_at"))));

        TARGETS.put("audit_events", new Target("audit_events", row -> new Document("_id", id(row, "id"))
                .append("timestamp", date(row, "timestamp"))
                .append("event", text(row, "event"))
                .append("request_id", text(row, "request_id"))
                .append("api_key_id", idOrText(row, "api_key_id"))
                .append("associated_object", idOrText(row, "associated_object"))
                .append("client_ip_address", text(row, "client_ip_address"))
                .append("source", text(row, "source"))
                .append("details", text(row, "details"))));

        TARGETS.put("pii_count_aggregates", new Target("pii_count_aggregates", row -> new Document("_id", new ObjectId())
                .append("context", text(row, "context"))
                .append("bucket_start", date(row, "bucket_start"))
                .append("total_documents", int32(row, "total_documents"))
                .append("counts", new Document())
                .append("updated_at", date(row, "updated_at"))));
    }

    /** The document a table's row becomes, or null for a table MongoDB storage has no collection for. */
    static Document documentFor(final String table, final Map<String, Object> row) {
        final Target target = TARGETS.get(table);
        return target == null ? null : target.toDocument().apply(row);
    }

    /**
     * Copies a table's records, handed over by {@code source}, and returns how many it copied.
     *
     * @throws IllegalArgumentException for a table MongoDB storage has no place for.
     */
    public long copy(final String table, final Consumer<Consumer<Map<String, Object>>> source) {
        if ("pii_counts".equals(table)) {
            return copyPiiCounts(source);
        }
        final Target target = TARGETS.get(table);
        if (target == null) {
            throw new IllegalArgumentException("MongoDB storage has no place for the table " + table + ".");
        }
        final MongoCollection<Document> collection = database.getCollection(target.collection());
        final List<Document> batch = new ArrayList<>();
        final long[] copied = {0};
        final long[] batchBytes = {0};
        source.accept(row -> {
            batch.add(target.toDocument().apply(row));
            batchBytes[0] += approximateSize(row);
            if (batch.size() == BATCH || batchBytes[0] >= BATCH_BYTES) {
                // A copy, since the batch is reused.
                collection.insertMany(List.copyOf(batch));
                copied[0] += batch.size();
                batch.clear();
                batchBytes[0] = 0;
            }
        });
        if (!batch.isEmpty()) {
            collection.insertMany(List.copyOf(batch));
            copied[0] += batch.size();
        }
        return copied[0];
    }

    /** Roughly how many bytes a row's values take: enough to keep a batch of large documents bounded. */
    private static long approximateSize(final Map<String, Object> row) {
        long size = 0;
        for (final Object value : row.values()) {
            size += value instanceof byte[] bytes ? bytes.length : value instanceof String text ? text.length() * 2L : 8;
        }
        return size;
    }

    /** MongoDB storage keeps a day's counts in its aggregate, one field per PII type. */
    private long copyPiiCounts(final Consumer<Consumer<Map<String, Object>>> source) {
        final MongoCollection<Document> aggregates = database.getCollection("pii_count_aggregates");
        final long[] copied = {0};
        source.accept(row -> {
            final long matched = aggregates.updateOne(
                    Filters.and(Filters.eq("context", text(row, "context")), Filters.eq("bucket_start", date(row, "bucket_start"))),
                    Updates.set("counts." + text(row, "pii_type"), int32(row, "count"))).getMatchedCount();
            if (matched != 1) {
                throw new IllegalStateException("The PII counts for context " + text(row, "context") + " on "
                        + date(row, "bucket_start").toInstant() + " have no aggregate to go in.");
            }
            copied[0]++;
        });
        return copied[0];
    }

    /** How many of a table's records MongoDB holds. */
    public long count(final String table) {
        if ("pii_counts".equals(table)) {
            long counts = 0;
            for (final Document aggregate : database.getCollection("pii_count_aggregates").find()) {
                final Document byType = aggregate.get("counts", Document.class);
                counts += byType == null ? 0 : byType.size();
            }
            return counts;
        }
        final Target target = TARGETS.get(table);
        if (target == null) {
            throw new IllegalArgumentException("MongoDB storage has no place for the table " + table + ".");
        }
        return database.getCollection(target.collection()).countDocuments();
    }

    /**
     * Checks the copy through the MongoDB services, and returns what did not verify: every ledger chain's hashes and
     * signatures, the active signing key and every retained public key, and a sample of each kind of encrypted field.
     */
    public List<String> verify() {
        final List<String> problems = new ArrayList<>();
        verifySigningKeys(problems);
        verifyLedger(problems);
        verifyEncryptedFields(problems);
        return problems;
    }

    private void verifySigningKeys(final List<String> problems) {
        final MongoCollection<Document> keys = database.getCollection("signing_keys");
        for (final Document key : keys.find()) {
            final String keyId = key.getString("key_id");
            try {
                KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                        SigningKeyDocuments.publicPartFromDocument(key).getPublicKeyEncoded()));
            } catch (final Exception ex) {
                problems.add("The public half of signing key " + keyId + " cannot be read: " + ex.getMessage());
            }
        }
        final Document active = database.getCollection("signing_key_state").find(Filters.eq("_id", "active")).first();
        if (active == null) {
            return;
        }
        final String activeKeyId = active.getString("key_id");
        final Document key = keys.find(Filters.eq("key_id", activeKeyId)).first();
        if (key == null) {
            problems.add("The active signing key " + activeKeyId + " was not copied.");
            return;
        }
        try {
            final SigningKeyEntity entity = SigningKeyDocuments.fromDocument(key, encryptionService);
            KeyFactory.getInstance("EC").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(entity.getPrivateKeyEncoded()));
        } catch (final Exception ex) {
            problems.add("The active signing key " + activeKeyId + " cannot be decrypted: " + ex.getMessage());
        }
    }

    private void verifyLedger(final List<String> problems) {
        final AuditEventPublisher audit = new MongoDBAuditEventPublisher(client);
        final LedgerDataService ledger = new MongoLedgerDataService(client, encryptionService, audit,
                new MongoLegalHoldDataService(client, audit, new MongoEvidenceGuard(client)),
                new SigningService(new CopiedPublicKeys(database.getCollection("signing_keys")), null));
        for (final Document chain : database.getCollection("ledger_chains").find()) {
            final String userId = MongoIds.hex(chain.getObjectId("user_id"));
            final String documentId = chain.getString("document_id");
            if (!ledger.chainExists(userId, documentId)) {
                continue;
            }
            final LedgerDataService.ChainValidation validation = ledger.validateChain(userId, documentId);
            if (!validation.valid()) {
                problems.add("The ledger chain for document " + documentId + " of user " + userId + " does not "
                        + "validate: hash chain " + (validation.hashChainValid() ? "valid" : "invalid") + ", signatures "
                        + (validation.signaturesValid() ? "valid" : "invalid")
                        + (validation.error() == null ? "" : ", " + validation.error()) + ".");
            }
        }
    }

    /** Each kind of encrypted field: its collection, ciphertext field, and data key field. */
    private record EncryptedField(String collection, String field, String keyField, boolean bytes) {
    }

    private static final List<EncryptedField> ENCRYPTED_FIELDS = List.of(
            new EncryptedField("users", "fpe_key", "fpe_key_key", false),
            new EncryptedField("users", "webhook_secret", "webhook_secret_key", false),
            new EncryptedField("users", "mfa_secret", "mfa_secret_key", false),
            new EncryptedField("users", "mfa_pending_secret", "mfa_pending_secret_key", false),
            new EncryptedField("admin_settings", "phield_api_key", "phield_api_key_key", false),
            new EncryptedField("custom_lists", "items", "items_encrypted_key", false),
            new EncryptedField("redact_lists", "terms_to_always_redact", "terms_to_always_redact_key", false),
            new EncryptedField("redact_lists", "terms_to_never_redact", "terms_to_never_redact_key", false),
            new EncryptedField("ledger", "token", "token_encrypted_key", false),
            new EncryptedField("ledger", "replacement", "replacement_encrypted_key", false),
            new EncryptedField("pending_documents", "effective_json", "effective_key", false),
            new EncryptedField("pending_documents", "input", "input_encrypted_key", true),
            new EncryptedField("pending_documents", "output", "output_encrypted_key", true),
            new EncryptedField("execution_snapshots", "effective_json", "effective_key", false),
            new EncryptedField("webhook_deliveries", "secret", "secret_encrypted_key", false),
            new EncryptedField("signing_keys", "private_key", "private_key_encrypted_key", true));

    private void verifyEncryptedFields(final List<String> problems) {
        for (final EncryptedField field : ENCRYPTED_FIELDS) {
            int checked = 0;
            for (final Document document : database.getCollection(field.collection())
                    .find(Filters.and(Filters.exists(field.keyField()), Filters.ne(field.keyField(), ""),
                            Filters.ne(field.keyField(), null)))) {
                if (checked == SAMPLE) {
                    break;
                }
                try {
                    if (field.bytes()) {
                        encryptionService.decryptBytes(document.get(field.field(), Binary.class).getData(),
                                document.getString(field.keyField()));
                    } else {
                        encryptionService.decrypt(document.getString(field.field()), document.getString(field.keyField()));
                    }
                } catch (final RuntimeException ex) {
                    problems.add("The " + field.field() + " of a record in " + field.collection() + " does not decrypt "
                            + "with this PHILTER_ENCRYPTION_KEY: " + ex.getMessage());
                }
                checked++;
            }
        }
    }

    /** Records the migration in the audit log: when, from where, and how many records of each kind it copied. */
    public void recordMigration(final String source, final Map<String, Long> copied) {
        final StringBuilder details = new StringBuilder("from: sqlite, source: ").append(source);
        copied.forEach((table, count) -> details.append(", ").append(table).append(": ").append(count));
        new MongoDBAuditEventPublisher(client).auditEvent("storage-migration", AuditLogEvent.STORAGE_MIGRATED, null,
                null, "system", details.toString());
    }

    @Override
    public void close() {
        if (ownsClient) {
            client.close();
        }
    }

    /**
     * The copied signing keys, read only for verifying signatures. Unlike the signing key service, it never makes a
     * key, so verifying cannot change what was copied.
     */
    private static final class CopiedPublicKeys implements SigningKeyDataService {

        private final MongoCollection<Document> keys;

        CopiedPublicKeys(final MongoCollection<Document> keys) {
            this.keys = keys;
        }

        @Override
        public PublicKey findPublicKeyById(final String keyId) {
            final Document key = keys.find(Filters.eq("key_id", keyId)).first();
            if (key == null) {
                return null;
            }
            try {
                return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(
                        SigningKeyDocuments.publicPartFromDocument(key).getPublicKeyEncoded()));
            } catch (final Exception ex) {
                return null;
            }
        }

        @Override
        public boolean isExternallyManaged() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String regenerate(final String requestId, final String actingUserId, final String clientIpAddress,
                                 final String details) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getActiveKeyId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SigningKey currentSigningKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublicKey getPublicKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPublicKeyPem(final String keyId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPublicKeyPem() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublicKeyInfo getPublicKeyInfo() {
            throw new UnsupportedOperationException();
        }

    }

}
