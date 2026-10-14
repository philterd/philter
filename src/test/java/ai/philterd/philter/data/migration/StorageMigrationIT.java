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
package ai.philterd.philter.data.migration;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.philter.data.contract.StorageUnderTest;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.mongodb.MongoStorageUnderTest;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.sqlite.SqliteExport;
import ai.philterd.philter.data.sqlite.SqliteInstanceLock;
import ai.philterd.philter.data.sqlite.SqliteStorageUnderTest;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptedBytes;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.KeyProvider;
import ai.philterd.philter.services.encryption.KeyResponse;
import ai.philterd.philter.services.mfa.TotpService;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fills a SQLite database through the SQLite services with every kind of record, migrates it to an in-memory
 * MongoDB, and checks the result through the MongoDB services.
 */
class StorageMigrationIT {

    private static final String POLICY = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    private static final String POLICY_CHANGED =
            "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @TempDir
    Path directory;

    private final EncryptionService encryption = new TestEncryptionService();
    private MongoServer server;
    private String connectionString;
    private MongoClient mongoClient;
    private String sqlitePath;

    @BeforeEach
    void start() {
        server = new MongoServer(new MemoryBackend());
        final InetSocketAddress address = server.bind();
        connectionString = "mongodb://" + address.getHostName() + ":" + address.getPort();
        mongoClient = MongoClients.create(connectionString);
        sqlitePath = directory.resolve("philter.db").toString();
    }

    @AfterEach
    void stop() {
        mongoClient.close();
        server.shutdown();
    }

    /** What the filled SQLite database holds, to look for after the migration. */
    private record Filled(String adminId, String userId, String password, String mfaSecret, String apiKey,
                          String sessionKey, String challenge, String firstKeyId, String secondKeyId,
                          String replacement, String documentId, byte[] output) {
    }

    private static PendingDocumentEntity job(final String owner, final String effectiveJson) {
        final PendingDocumentEntity job = new PendingDocumentEntity();
        job.setUserId(owner);
        job.setDocumentId("async-" + owner);
        job.setStatus(PendingDocumentEntity.STATUS_PENDING);
        job.setSubmittedAt(new Date());
        job.setInput("a document to redact".getBytes(StandardCharsets.UTF_8));
        job.setEffectiveJson(effectiveJson);
        job.setEffectiveHash(PolicyVersionDataService.contentHash(effectiveJson));
        return job;
    }

    private static void chain(final StorageUnderTest storage, final String owner, final String documentId,
                              final String token) throws Exception {
        final LedgerDataService ledger = storage.ledger();
        ledger.initializeLedger(owner, documentId, "input-hash", "file.pdf", "policy", 1, "policy-hash");
        for (int i = 0; i < 3; i++) {
            final LedgerEntity entry = new LedgerEntity(owner, documentId, token + "-" + i, "{{{REDACTED-ssn}}}", i * 10,
                    "input-hash", ledger.getLatestTransaction(owner, documentId).getHash(), "file.pdf", "ssn", "policy", 1,
                    "policy-hash");
            ledger.addTransaction(entry);
        }
        ledger.completeChain(owner, documentId);
    }

    /** Every kind of record SQLite storage holds, written through its services. */
    private Filled fill() throws Exception {
        final SqliteStorageUnderTest storage = SqliteStorageUnderTest.create(sqlitePath, encryption);
        try {
            assertTrue(storage.users().createUser("req", "admin", null, UserService.ROLE_ADMIN, storage.policies(),
                    storage.contexts(), "test").isSuccessful());
            final String adminId = storage.users().findByUsername("admin").getId();
            assertTrue(storage.users().createUser("req", "jordan", "jordan@example.com", UserService.ROLE_USER,
                    storage.policies(), storage.contexts(), "test").isSuccessful());
            final UserEntity jordan = storage.users().findByUsername("jordan");
            final String password = "a long password for jordan";
            assertTrue(storage.users().setPassword("req", jordan, password, false, "test", null, null).isSuccessful());
            final String mfaSecret = storage.users().startMfaEnrollment(storage.users().findOneById(jordan.getId()));
            assertTrue(storage.users().confirmMfaEnrollment("req", storage.users().findOneById(jordan.getId()),
                    new TotpService().codeAt(mfaSecret, TotpService.currentTimeStep() - 1), "test", null).isSuccessful());
            assertTrue(storage.users().setWebhook("req", storage.users().findOneById(jordan.getId()),
                    "https://93.184.215.14/hook", "webhook-secret-0123456789abcdef", null, "test", null, null).isSuccessful());

            final String apiKey = storage.apiKeys().createApiKey("req", jordan.getId(), "test",
                    Set.of(ApiKeyScope.REDACT.getScope(), ApiKeyScope.POLICIES_READ.getScope())).getMessage();
            final String sessionKey = storage.apiKeys().createSessionKey("req", jordan.getId(),
                    Set.of(ApiKeyScope.USERS_READ.getScope()), false, false, "test", null, "203.0.113.9", "Browser/1.0")
                    .getApiKey();
            final String challenge = storage.signInChallenges().create(jordan.getId()).token();

            storage.adminSettings().update(new AdminSettingsDataService.Update(true, true, "example.com", null, null,
                    null, null, "phield key", null, null), adminId, null);

            // A chain signed with the first key, then one signed with the second.
            final String firstKeyId = storage.signingKeys().getActiveKeyId();
            chain(storage, jordan.getId(), "doc-1", "123-45-6789");
            storage.signingKeys().regenerate("req", adminId, null, "test");
            final String secondKeyId = storage.signingKeys().getActiveKeyId();
            chain(storage, jordan.getId(), "doc-2", "987-65-4321");

            assertTrue(storage.policies().createPolicy("req", jordan.getId(), POLICY, "description", "notes", "claims",
                    "test", jordan.getId()).response().isSuccessful());
            final var claims = storage.policies().findOne("claims", jordan.getId());
            assertTrue(storage.policies().replace("req", jordan.getId(), claims.getId(), POLICY_CHANGED, null, null,
                    "test", jordan.getId(), ai.philterd.philter.data.services.PolicyDataService.eTag(claims))
                    .response().isSuccessful());
            assertTrue(storage.policies().createPolicy("req", jordan.getId(), POLICY, null, null, "retired", "test",
                    jordan.getId()).response().isSuccessful());
            assertTrue(storage.policies().deleteByName("req", "retired", jordan.getId(), Source.API, jordan.getId(), null)
                    .isSuccessful());

            assertTrue(storage.customLists().saveOrUpdate("req", jordan.getId(), "names", "Names", List.of("Ünïcödé",
                    "with, comma"), false, "test").isSuccessful());
            storage.redactLists().saveOrUpdate("req", jordan.getId(), List.of("always"), List.of("never"), "test");

            assertTrue(storage.contexts().create("claims", jordan.getId(), true, true).isSuccessful());
            final String replacement = storage.contextEntries().putReplacementIfAbsent(jordan.getId(), "claims",
                    "Jordan Smith", "{{{PERSON-1}}}", "PERSON").getReplacement();
            final Span span = new Span();
            span.setFilterType(FilterType.PERSON);
            storage.vectors().forUser(jordan.getId()).hashAndInsert("claims", new double[]{1.5, 2.5}, span, 2);

            assertEquals(201, storage.legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc-1",
                    "litigation", jordan.getId(), adminId).getStatusCode());

            // A finished asynchronous document, and its webhook delivery.
            storage.pendingDocuments().save(job(jordan.getId(), "{\"identifiers\":{}}"));
            final PendingDocumentEntity claim = storage.pendingDocuments().claimNextPending("worker");
            final byte[] output = "the redacted document".getBytes(StandardCharsets.UTF_8);
            assertTrue(storage.pendingDocuments().beginPublication(claim.getId(), claim.getClaimToken()));
            assertTrue(storage.pendingDocuments().markComplete(claim.getId(), jordan.getId(), claim.getClaimToken(), output));
            final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
            delivery.setId(claim.getId());
            delivery.setUserId(jordan.getId());
            delivery.setDocumentId(claim.getDocumentId());
            delivery.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
            delivery.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
            delivery.setUrl("https://example.com/hook");
            delivery.setSecret("delivery secret");
            delivery.setPayload("{}");
            delivery.setNextAttemptAt(new Date());
            delivery.setCreatedAt(new Date());
            delivery.setUpdatedAt(new Date());
            storage.webhookDeliveries().enqueueOnce(delivery);

            storage.piiCounts().record(jordan.getId(), "claims", Set.of("PERSON", "SSN"));
            storage.piiCounts().record(jordan.getId(), "claims", Set.of("PERSON"));

            return new Filled(adminId, jordan.getId(), password, mfaSecret, apiKey, sessionKey, challenge, firstKeyId,
                    secondKeyId, replacement, claim.getDocumentId(), output);
        } finally {
            storage.close();
        }
    }

    private static String sha256(final Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private StorageMigration.Result migrate() {
        return StorageMigration.sqliteToMongoDb(sqlitePath, connectionString, encryption, Clock.systemUTC());
    }

    @Test
    @DisplayName("Every kind of record is copied and is usable through the MongoDB services")
    void everythingIsCopiedAndUsable() throws Exception {

        final Filled filled = fill();
        final String before = sha256(Path.of(sqlitePath));

        final StorageMigration.Result result = migrate();
        assertTrue(result.succeeded(), result.problems().toString());
        // The test fills every table the migration copies, so a table added later is caught here if it is not.
        for (final String table : SqliteExport.TABLES) {
            assertTrue(result.copied().get(table) > 0, "copied no " + table + ": " + result.copied());
        }
        assertEquals(before, sha256(Path.of(sqlitePath)), "the SQLite database is unchanged");

        final MongoStorageUnderTest mongo = new MongoStorageUnderTest(mongoClient, encryption);

        // Users sign in with their passwords and MFA, and keep their secrets.
        final UserEntity jordan = mongo.users().findByUsername("jordan");
        assertEquals(filled.userId(), jordan.getId());
        assertTrue(mongo.users().passwordMatches(jordan, filled.password()));
        assertTrue(jordan.isMfaEnabled());
        assertEquals(UserService.MfaCheck.ACCEPTED, mongo.users().checkMfaCode("req", jordan,
                new TotpService().codeAt(filled.mfaSecret(), TotpService.currentTimeStep() + 1), "test"));
        assertEquals("webhook-secret-0123456789abcdef", mongo.users().findOneById(filled.userId()).getWebhookSecret());
        assertEquals(1, mongo.users().countActiveAdmins());

        // API keys keep working, session keys keep their details, and a sign-in challenge is still good once.
        final ApiKeyEntity key = mongo.apiKeys().findOneByApiKey(filled.apiKey());
        assertNotNull(key);
        assertEquals(Set.of(ApiKeyScope.REDACT.getScope(), ApiKeyScope.POLICIES_READ.getScope()), key.getScopes());
        final ApiKeyEntity session = mongo.apiKeys().findOneByApiKey(filled.sessionKey());
        assertTrue(session.isSession());
        assertEquals("203.0.113.9", session.getClientAddress());
        assertTrue(mongo.apiKeys().touchSessionKey(session));
        assertEquals(filled.userId(), mongo.signInChallenges().consume(filled.challenge()));

        // Ledger chains validate, including signatures made with the key since superseded.
        assertEquals(filled.secondKeyId(), mongo.signingKeys().getActiveKeyId());
        assertNotNull(mongo.signingKeys().findPublicKeyById(filled.firstKeyId()));
        for (final String documentId : List.of("doc-1", "doc-2")) {
            final LedgerDataService.ChainValidation validation = mongo.ledger().validateChain(filled.userId(), documentId);
            assertTrue(validation.valid(), documentId + ": " + validation);
            assertEquals(4, mongo.ledger().getChain(filled.userId(), documentId).size());
        }
        assertEquals("123-45-6789-2", mongo.ledger().getLatestTransaction(filled.userId(), "doc-1").getToken());
        // The legal hold still protects its chain.
        assertTrue(mongo.legalHolds().isProtectedDocument(filled.userId(), "doc-1"));
        assertEquals(423, mongo.ledger().deleteByDocumentId("req", filled.userId(), "doc-1", "test").getStatusCode());

        // Policies with their version history, and the deleted policy's history.
        assertEquals(POLICY_CHANGED, mongo.policies().findOne("claims", filled.userId()).getPolicy());
        assertEquals(2, mongo.policyVersions().listByName("claims", filled.userId(), false, 0, 10).total());
        // Revisions keep counting from where they were: the next change is revision 3, never a reused one.
        final var claims = mongo.policies().findOne("claims", filled.userId());
        final var changed = mongo.policies().replace("req", filled.userId(), claims.getId(), POLICY, null, null, "test",
                filled.userId(), ai.philterd.philter.data.services.PolicyDataService.eTag(claims));
        assertTrue(changed.response().isSuccessful(), changed.response().getMessage());
        assertEquals(claims.getRevision() + 1, changed.policy().getRevision());
        assertEquals(3, mongo.policyVersions().listByName("claims", filled.userId(), false, 0, 10).total());
        assertEquals(1, mongo.policies().findDeleted(filled.userId(), null, true, 0, 10).total());

        // Lists, contexts with their mappings, vectors, and settings.
        assertEquals(List.of("Ünïcödé", "with, comma"), mongo.customLists().findOneByName("names", filled.userId()).getItems());
        assertEquals(List.of("always"), mongo.redactLists().find(filled.userId()).getTermsToAlwaysRedact());
        assertTrue(mongo.contexts().findOneByNameAndUserId("claims", filled.userId()).isLedger());
        assertEquals(filled.replacement(), mongo.contextEntries().getReplacement(filled.userId(), "claims", "Jordan Smith"));
        assertEquals(2, mongo.vectors().forUser(filled.userId()).getVectorRepresentation("claims", FilterType.PERSON).size());
        assertEquals("phield key", mongo.adminSettings().findAdminSettings().getPhieldApiKey());
        assertTrue(mongo.adminSettings().findAdminSettings().isSigningEnabled());

        // The finished document and its notification, the webhook delivery, and the PII counts.
        final PendingDocumentEntity finished = mongo.pendingDocuments().findOneByDocumentIdAndUserId(filled.documentId(),
                filled.userId());
        assertArrayEquals(filled.output(), finished.getOutput());
        assertEquals(1, mongo.pendingDocuments().pendingNotifications(10).size());
        assertEquals("delivery secret", mongo.webhookDeliveries().claimNextDue(new Date()).getSecret());
        final Map<String, Long> counts = mongo.piiCountData().list(LocalDate.now(ZoneOffset.UTC), LocalDate.now(ZoneOffset.UTC),
                null, "claims", 0, 10).items().get(0).counts();
        assertEquals(Map.of("PERSON", 2L, "SSN", 1L), counts);

        // The audit log came along, and records the migration.
        assertTrue(mongo.auditLog().count(null, AuditLogEvent.USER_CREATED.getAuditLogEvent(), null, null) >= 2);
        final var migrated = mongo.auditLog().find(null, AuditLogEvent.STORAGE_MIGRATED.getAuditLogEvent(), null, null, 0, 10);
        assertEquals(1, migrated.size());
        assertTrue(migrated.get(0).details().contains("source: " + sqlitePath), migrated.get(0).details());
        assertEquals(1, new Document(mongoClient.getDatabase("philter").getCollection("schema_version")
                .find(new Document("_id", "version")).first()).getInteger("version"));

    }

    @Test
    @DisplayName("Every table SQLite storage creates is either copied or deliberately left out")
    void everyTableIsAccountedFor() throws Exception {
        SqliteStorageUnderTest.create(sqlitePath, encryption).close();
        final java.util.Set<String> tables = new java.util.TreeSet<>();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var rows = connection.createStatement().executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")) {
            while (rows.next()) {
                tables.add(rows.getString("name"));
            }
        }
        final java.util.Set<String> accounted = new java.util.TreeSet<>(SqliteExport.TABLES);
        // The schema version, its history and lock are recorded by the target itself; the queue admission and
        // evidence guards hold nothing once Philter has stopped.
        accounted.addAll(List.of("schema_version", "schema_history", "schema_lock", "queue_admission", "evidence_guards"));
        assertEquals(accounted, tables, "a table SQLite storage creates must be copied by the migration (SqliteExport.TABLES "
                + "and MongoImport) or deliberately left out here");
    }

    @Test
    @DisplayName("A MongoDB that already holds Philter data is refused, and nothing is written")
    void aTargetWithDataIsRefused() throws Exception {
        fill();
        mongoClient.getDatabase("philter").getCollection("users").insertOne(new Document("username", "someone"));

        assertThrows(StorageMigration.Refused.class, this::migrate);

        assertEquals(List.of("users"), mongoClient.getDatabase("philter").listCollectionNames().into(new java.util.ArrayList<>()));
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("users").countDocuments());
    }

    @Test
    @DisplayName("A SQLite database a running Philter holds is refused, and nothing is written")
    void aDatabaseInUseIsRefused() throws Exception {
        fill();
        try (SqliteInstanceLock running = SqliteInstanceLock.acquire(sqlitePath)) {
            assertThrows(StorageMigration.Refused.class, this::migrate);
        }
        assertFalse(mongoClient.getDatabase("philter").listCollectionNames().iterator().hasNext());
    }

    @Test
    @DisplayName("A lock a stopped Philter left held is refused, so SQLite storage can clear and audit it first")
    void aHeldLockIsRefused() throws Exception {
        fill();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO evidence_guards (owner, token, operation, started_at) VALUES "
                    + "('0123456789abcdef01234567', 'held', 'purge_by_age', 0)");
        }
        final StorageMigration.Refused refused = assertThrows(StorageMigration.Refused.class, this::migrate);
        assertTrue(refused.getMessage().contains("left its lock held"), refused.getMessage());
        assertFalse(mongoClient.getDatabase("philter").listCollectionNames().iterator().hasNext());
    }

    @Test
    @DisplayName("A SQLite database that is not there is refused, and no lock file is left behind")
    void aMissingDatabaseIsRefused() {
        assertThrows(StorageMigration.Refused.class, this::migrate);
        assertFalse(Files.exists(Path.of(sqlitePath + ".lock")));
        assertFalse(mongoClient.getDatabase("philter").listCollectionNames().iterator().hasNext());
    }

    @Test
    @DisplayName("A MongoDB that cannot be reached is reported as nothing migrated, and the SQLite lock is released")
    void anUnreachableMongoDbIsReportedAsNothingMigrated() throws Exception {
        fill();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = MigrateStorageCommand.run(List.of("--to", "mongodb"),
                Map.of("SQLITE_PATH", sqlitePath, "MONGODB_CONNECTION_STRING", "mongodb://localhost:1/?serverSelectionTimeoutMS=500"),
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err), () -> encryption, Clock.systemUTC());
        assertEquals(MigrateStorageCommand.FAILED, status);
        final String errors = err.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("Nothing was migrated"), errors);
        assertFalse(errors.contains("may hold part of the data"), errors);
        // The SQLite database is free for Philter again.
        SqliteInstanceLock.acquire(sqlitePath).close();
    }

    @Test
    @DisplayName("A SQLite database at another schema version is refused, and nothing is written")
    void anotherSchemaVersionIsRefused() throws Exception {
        fill();
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE schema_version SET version = 2");
        }
        final StorageMigration.Refused refused = assertThrows(StorageMigration.Refused.class, this::migrate);
        assertTrue(refused.getMessage().contains("schema version 2"), refused.getMessage());
        assertFalse(mongoClient.getDatabase("philter").listCollectionNames().iterator().hasNext());
    }

    @Test
    @DisplayName("A copy that does not verify is reported, not recorded as a migration, and the command fails")
    void aCopyThatDoesNotVerifyFails() throws Exception {
        fill();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();

        // A different PHILTER_ENCRYPTION_KEY: nothing encrypted decrypts.
        final int status = MigrateStorageCommand.run(List.of("--to", "mongodb"),
                Map.of("SQLITE_PATH", sqlitePath, "MONGODB_CONNECTION_STRING", connectionString), new PrintStream(out),
                new PrintStream(err), CannotDecrypt::new, Clock.systemUTC());

        assertEquals(MigrateStorageCommand.FAILED, status);
        final String errors = err.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("does not decrypt"), errors);
        assertTrue(errors.contains("ledger chain"), errors);
        assertEquals(0, mongoClient.getDatabase("philter").getCollection("audit_events")
                .countDocuments(new Document("event", AuditLogEvent.STORAGE_MIGRATED.getAuditLogEvent())));
    }

    @Test
    @DisplayName("The command migrates with exit status 0, and asks for what it needs")
    void theCommand() throws Exception {
        fill();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final PrintStream outStream = new PrintStream(out);
        final PrintStream errStream = new PrintStream(err);

        assertEquals(MigrateStorageCommand.USAGE, MigrateStorageCommand.run(List.of(), Map.of(), outStream, errStream,
                () -> encryption, Clock.systemUTC()));
        assertEquals(MigrateStorageCommand.USAGE, MigrateStorageCommand.run(List.of("--to", "sqlite"), Map.of(), outStream,
                errStream, () -> encryption, Clock.systemUTC()));
        assertEquals(MigrateStorageCommand.USAGE, MigrateStorageCommand.run(List.of("--to", "mongodb"),
                Map.of("SQLITE_PATH", sqlitePath), outStream, errStream, () -> encryption, Clock.systemUTC()));
        assertFalse(mongoClient.getDatabase("philter").listCollectionNames().iterator().hasNext());

        assertEquals(MigrateStorageCommand.SUCCEEDED, MigrateStorageCommand.run(List.of("--to", "mongodb"),
                Map.of("SQLITE_PATH", sqlitePath, "MONGODB_CONNECTION_STRING", connectionString), outStream, errStream,
                () -> encryption, Clock.systemUTC()), err.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Migrated and verified"));

        // Run again, it refuses: the MongoDB now holds Philter data.
        assertEquals(MigrateStorageCommand.FAILED, MigrateStorageCommand.run(List.of("--to", "mongodb"),
                Map.of("SQLITE_PATH", sqlitePath, "MONGODB_CONNECTION_STRING", connectionString), outStream, errStream,
                () -> encryption, Clock.systemUTC()));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("already holds Philter data"));
    }

    /** An encryption service with another key: what it decrypts was encrypted with a different one. */
    private static final class CannotDecrypt extends EncryptionService {

        CannotDecrypt() {
            super(new KeyProvider() {
                @Override
                public KeyResponse getKey(final String userId) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String decryptKey(final String storedKey) {
                    throw new UnsupportedOperationException();
                }
            });
        }

        @Override
        public String generateEncryptionKey() {
            throw new UnsupportedOperationException();
        }

        @Override
        public EncryptResult encrypt(final String data, final String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String decrypt(final String encryptedText, final String encryptedDataKey) {
            throw new IllegalStateException("Unable to decrypt with this key.");
        }

        @Override
        public EncryptedBytes encryptBytes(final byte[] data, final String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] decryptBytes(final byte[] encrypted, final String encryptedDataKey) {
            throw new IllegalStateException("Unable to decrypt with this key.");
        }
    }

}
