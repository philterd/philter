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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.contract.StorageUnderTest;
import ai.philterd.philter.data.schema.SchemaMigrator;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.EvidenceGuard;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PiiCountDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.SignInChallengeDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.services.vectors.VectorServices;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.google.gson.Gson;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The SQLite implementation for the storage contracts. Each instance opens its own connection to the database
 * file, as another Philter process would; SQLite storage supports one instance, but its writes must still be
 * atomic across connections.
 */
public final class SqliteStorageUnderTest implements StorageUnderTest {

    private final String path;
    private final SqliteDatabase database;
    private final List<SqliteDatabase> opened;
    private final EncryptionService encryption;
    private final ContextCache contextCache = new ContextCache("", 0, "", false);
    private final ApiKeyCache apiKeyCache = new ApiKeyCache("", 0, "", false);
    private final Map<String, Object> services = new HashMap<>();

    /** Opens a new database file at {@code path}, brought to the current schema version. */
    static SqliteStorageUnderTest create(final String path) {
        return create(path, new TestEncryptionService());
    }

    /** As above, encrypting with {@code encryption}. */
    public static SqliteStorageUnderTest create(final String path, final EncryptionService encryption) {
        final List<SqliteDatabase> opened = new ArrayList<>();
        final SqliteStorageUnderTest storage = new SqliteStorageUnderTest(path, opened, encryption);
        new SchemaMigrator(new SqliteSchemaStore(storage.database, SqliteMigrations.all(storage.database)),
                SqliteMigrations.all(storage.database), Duration.ofSeconds(10)).migrate();
        return storage;
    }

    private SqliteStorageUnderTest(final String path, final List<SqliteDatabase> opened, final EncryptionService encryption) {
        this.path = path;
        this.encryption = encryption;
        this.database = new SqliteDatabase(path);
        this.opened = opened;
        opened.add(database);
    }

    /** Closes every connection this storage and its other instances opened. */
    public void close() {
        opened.forEach(SqliteDatabase::close);
    }

    /** Each service is created once and shared, also by concurrent callers. */
    @SuppressWarnings("unchecked")
    private synchronized <T> T once(final String name, final Supplier<T> create) {
        Object service = services.get(name);
        if (service == null) {
            service = create.get();
            services.put(name, service);
        }
        return (T) service;
    }

    @Override
    public EvidenceGuard evidenceGuard() {
        return once("evidenceGuard", () -> new SqliteEvidenceGuard(database));
    }

    @Override
    public AdminSettingsDataService adminSettings() {
        return once("adminSettings", () -> new SqliteAdminSettingsDataService(database, encryption, auditEvents()));
    }

    @Override
    public ApiKeyDataService apiKeys() {
        return once("apiKeys", () -> new SqliteApiKeyDataService(database, auditEvents(), apiKeyCache));
    }

    @Override
    public ContextDataService contexts() {
        return once("contexts", () -> new SqliteContextDataService(database, auditEvents(), contextEntries(), vectors()));
    }

    @Override
    public VectorServices vectors() {
        return once("vectors", () -> new SqliteVectorServices(database));
    }

    @Override
    public ContextEntryDataService contextEntriesHolding(final int capacity) {
        return new SqliteContextEntryDataService(database, contextCache, capacity);
    }

    @Override
    public ContextEntryDataService contextEntries() {
        return once("contextEntries", () -> new SqliteContextEntryDataService(database, contextCache));
    }

    @Override
    public CustomListDataService customLists() {
        return once("customLists", () -> new SqliteCustomListDataService(database, encryption, auditEvents()));
    }

    @Override
    public LedgerDataService ledger() {
        return once("ledger", () -> new SqliteLedgerDataService(database, encryption, auditEvents(), legalHolds(),
                new SigningService(signingKeys(), adminSettings()), evidenceGuard()));
    }

    @Override
    public LegalHoldDataService legalHolds() {
        return once("legalHolds", () -> new SqliteLegalHoldDataService(database, auditEvents(), evidenceGuard()));
    }

    @Override
    public PendingDocumentDataService pendingDocuments() {
        return once("pendingDocuments", () -> new SqlitePendingDocumentDataService(database, encryption));
    }

    @Override
    public PendingDocumentDataService pendingDocumentsEncryptingWith(final EncryptionService encryption) {
        return new SqlitePendingDocumentDataService(database, encryption);
    }

    @Override
    public PendingDocumentDataService pendingDocumentsAt(final LongSupplier clock) {
        return new SqlitePendingDocumentDataService(database, encryption, clock);
    }

    @Override
    public PolicyDataService policies() {
        return once("policies", () -> new SqlitePolicyDataService(database, encryption, auditEvents(), new Gson(), policyVersions(),
                new RedactionCache()));
    }

    @Override
    public PolicyVersionDataService policyVersions() {
        return once("policyVersions", () -> new SqlitePolicyVersionDataService(database, encryption));
    }

    @Override
    public RedactListsDataService redactLists() {
        return once("redactLists", () -> new SqliteRedactListsDataService(database, encryption, auditEvents()));
    }

    @Override
    public SignInChallengeDataService signInChallenges() {
        return once("signInChallenges", () -> new SqliteSignInChallengeDataService(database));
    }

    @Override
    public SigningKeyDataService signingKeys() {
        return once("signingKeys", () -> new SqliteSigningKeyDataService(database, encryption, auditEvents(), null));
    }

    @Override
    public UserService users() {
        return once("users", () -> new SqliteUserService(database, encryption, auditEvents()));
    }

    @Override
    public UserService usersDecryptingWith(final EncryptionService encryption) {
        return new SqliteUserService(database, encryption, auditEvents());
    }

    @Override
    public WebhookDeliveryDataService webhookDeliveries() {
        return once("webhookDeliveries", () -> new SqliteWebhookDeliveryDataService(database, encryption));
    }

    @Override
    public AuditEventPublisher auditEvents() {
        return once("auditEvents", () -> new SqliteAuditEventPublisher(database));
    }

    @Override
    public AuditLogService auditLog() {
        return once("auditLog", () -> new SqliteAuditLogService(database));
    }

    @Override
    public PiiCountAggregatePublisher piiCounts() {
        return once("piiCounts", () -> new SqlitePiiCountAggregatePublisher(database, adminSettings()));
    }

    @Override
    public PiiCountDataService piiCountData() {
        return once("piiCountData", () -> new SqlitePiiCountDataService(database));
    }

    @Override
    public boolean storageContains(final String text) {
        return database.read(connection -> {
            for (final String table : Sql.query(connection, "SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%'", row -> row.string("name"))) {
                try (java.sql.Statement statement = connection.createStatement();
                     java.sql.ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
                    final int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        for (int column = 1; column <= columns; column++) {
                            final byte[] value = rows.getBytes(column);
                            if (value != null && new String(value, java.nio.charset.StandardCharsets.UTF_8).contains(text)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        });
    }

    @Override
    public boolean storageContainsBytes(final byte[] bytes) {
        return database.read(connection -> {
            for (final String table : Sql.query(connection, "SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%'", row -> row.string("name"))) {
                try (java.sql.Statement statement = connection.createStatement();
                     java.sql.ResultSet rows = statement.executeQuery("SELECT * FROM " + table)) {
                    final int columns = rows.getMetaData().getColumnCount();
                    while (rows.next()) {
                        for (int column = 1; column <= columns; column++) {
                            final byte[] value = rows.getBytes(column);
                            if (value != null && StorageUnderTest.containsBytes(value, bytes)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        });
    }

    @Override
    public void damageLedgerChain(final String userId, final String documentId) {
        database.write(connection -> Sql.update(connection, "UPDATE ledger SET replacement_encrypted_key = 'not-a-key', "
                + "token_encrypted_key = 'not-a-key' WHERE user_id = ? AND document_id = ?", userId, documentId));
    }

    @Override
    public void damageLedgerTokens(final String userId, final String documentId) {
        database.write(connection -> Sql.update(connection, "UPDATE ledger SET token_encrypted_key = 'not-a-key' "
                + "WHERE user_id = ? AND document_id = ?", userId, documentId));
    }

    @Override
    public void backdateLedgerChainCompletion(final String userId, final String documentId, final Date completedAt) {
        database.write(connection -> Sql.update(connection, "UPDATE ledger_chains SET completed_at = ? WHERE user_id = ? "
                + "AND document_id = ?", completedAt, userId, documentId));
    }

    @Override
    public StorageUnderTest anotherInstance() {
        return new SqliteStorageUnderTest(path, opened, encryption);
    }

    @Override
    public void expireSessionKey(final String keyId) {
        final Date past = new Date(System.currentTimeMillis() - 60_000);
        database.write(connection -> Sql.update(connection, "UPDATE api_keys SET expires_at = ?, idle_expires_at = ? WHERE id = ?",
                past, past, keyId));
    }

    @Override
    public void expireSessionIdleWindow(final String keyId) {
        database.write(connection -> Sql.update(connection, "UPDATE api_keys SET idle_expires_at = ? WHERE id = ?",
                new Date(System.currentTimeMillis() - 60_000), keyId));
    }

    @Override
    public void expireSessionLifetime(final String keyId) {
        database.write(connection -> Sql.update(connection, "UPDATE api_keys SET expires_at = ? WHERE id = ?",
                new Date(System.currentTimeMillis() - 60_000), keyId));
    }

    @Override
    public void expireSignInChallenge(final String token) {
        database.write(connection -> Sql.update(connection, "UPDATE sign_in_challenges SET expires_at = ? WHERE token_hash = ?",
                new Date(System.currentTimeMillis() - 60_000), EncryptionService.hashSha256(token)));
    }

    @Override
    public void storePiiCounts(final String context, final LocalDate day, final Map<String, Long> counts,
                               final long totalDocuments) {
        final Date bucketStart = Date.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
        database.write(connection -> {
            Sql.update(connection, "INSERT INTO pii_count_aggregates (context, bucket_start, total_documents, updated_at) "
                    + "VALUES (?, ?, ?, ?)", context, bucketStart, totalDocuments, bucketStart);
            for (final Map.Entry<String, Long> count : counts.entrySet()) {
                Sql.update(connection, "INSERT INTO pii_counts (context, bucket_start, pii_type, count) VALUES (?, ?, ?, ?)",
                        context, bucketStart, count.getKey(), count.getValue());
            }
            return null;
        });
    }

}
