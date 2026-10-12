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

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.model.AuditLogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards left held by a Philter that stopped are cleared when SQLite storage next starts, and only then. */
class SqliteGuardRecoveryTest {

    @TempDir
    Path directory;

    /** Starts SQLite storage as Philter does: the instance lock, the database, then the schema and recovery. */
    private static SqliteInstanceLock start(final String path) {
        final SqliteConnectionConfiguration configuration = new SqliteConnectionConfiguration(path);
        final SqliteInstanceLock lock = configuration.sqliteInstanceLock();
        try (SqliteDatabase database = configuration.sqliteDatabase(lock)) {
            configuration.schemaVersion(database);
        }
        return lock;
    }

    @Test
    @DisplayName("Guards a stopped Philter left held are cleared at the next start, logged in the audit log")
    void guardsLeftByAStoppedPhilterAreClearedAtStartup() throws Exception {
        final String path = directory.resolve("philter.db").toString();
        start(path).close();

        // A Philter that stopped part way through an admission and an evidence operation.
        final String owner = RecordIds.newId();
        try (SqliteDatabase database = new SqliteDatabase(path)) {
            assertTrue(new SqliteAdmissionGuard(database).tryAcquire("stopped"));
            assertNotNull(new SqliteEvidenceGuard(database).acquire(owner, "purge_by_age"));
        }

        start(path).close();

        try (SqliteDatabase database = new SqliteDatabase(path)) {
            assertTrue(new SqliteAdmissionGuard(database).tryAcquire("next"), "the admission guard is free again");
            assertNotNull(new SqliteEvidenceGuard(database).acquire(owner, "legal_hold"), "the evidence guard is free again");
            final List<AuditLogService.AuditEvent> recovered = new SqliteAuditLogService(database).find(null,
                    AuditLogEvent.STORAGE_GUARD_RECOVERED.getAuditLogEvent(), null, null, 0, 10);
            assertEquals(2, recovered.size());
            assertTrue(recovered.stream().anyMatch(event -> event.details().contains("guard: queue_admission")));
            assertTrue(recovered.stream().anyMatch(event -> owner.equals(event.apiKeyId())
                    && event.details().contains("operation: purge_by_age")), recovered.toString());
        }
    }

    @Test
    @DisplayName("A second Philter cannot start, so it never clears a running Philter's guards")
    void aRunningPhiltersGuardsAreNeverCleared() throws Exception {
        final String path = directory.resolve("philter.db").toString();
        final String owner = RecordIds.newId();
        try (SqliteInstanceLock running = start(path); SqliteDatabase database = new SqliteDatabase(path)) {
            assertNotNull(new SqliteEvidenceGuard(database).acquire(owner, "delete_document_chain"));

            assertThrows(IllegalStateException.class, () -> start(path));

            assertNull(new SqliteEvidenceGuard(database).acquire(owner, "legal_hold"), "still held by the running Philter");
        }
    }

    @Test
    @DisplayName("A start with nothing held records nothing")
    void nothingHeldRecordsNothing() throws Exception {
        final String path = directory.resolve("philter.db").toString();
        start(path).close();
        start(path).close();
        try (SqliteDatabase database = new SqliteDatabase(path)) {
            assertEquals(0, new SqliteAuditLogService(database).count(null,
                    AuditLogEvent.STORAGE_GUARD_RECOVERED.getAuditLogEvent(), null, null));
        }
    }

}
