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

import ai.philterd.philter.data.schema.Migration;
import ai.philterd.philter.data.schema.SchemaMigrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSchemaStoreTest {

    @TempDir
    Path directory;

    private SqliteDatabase database;

    @BeforeEach
    void open() {
        database = new SqliteDatabase(directory.resolve("philter.db").toString());
    }

    @AfterEach
    void close() {
        database.close();
    }

    private long tables() {
        return database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'schema_%' AND name NOT LIKE 'sqlite_%'"));
    }

    @Test
    void anEmptyDatabaseIsCreatedAtTheExpectedVersion() {
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of());
        assertNull(store.currentVersion());
        assertFalse(store.hasData());

        assertEquals(SchemaMigrator.BASELINE_VERSION, new SchemaMigrator(store, List.of(), Duration.ofSeconds(5)).migrate());

        assertEquals(SchemaMigrator.BASELINE_VERSION, store.currentVersion());
        assertTrue(tables() > 20, "the version 1 tables exist");
        // Starting again changes nothing.
        assertEquals(SchemaMigrator.BASELINE_VERSION, new SchemaMigrator(store, List.of(), Duration.ofSeconds(5)).migrate());
    }

    @Test
    void aNewDatabaseRunsTheMigrationsUpToItsVersion() {
        final Migration addColumn = new Migration() {
            public int version() { return 2; }
            public String description() { return "add a column"; }
            public void apply() {
                database.write(connection -> Sql.update(connection, "ALTER TABLE users ADD COLUMN nickname TEXT"));
            }
        };
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of(addColumn));
        assertEquals(2, new SchemaMigrator(store, List.of(addColumn), Duration.ofSeconds(5)).migrate());
        assertEquals(1, (long) database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM pragma_table_info('users') WHERE name = 'nickname'")));
    }

    @Test
    void aCreationThatStopsPartWayResumesOnTheNextStart() {
        final Migration failing = new Migration() {
            public int version() { return 2; }
            public String description() { return "add a column"; }
            public void apply() {
                throw new IllegalStateException("stopped");
            }
        };
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of(failing));
        assertThrows(IllegalStateException.class, () -> new SchemaMigrator(store, List.of(failing), Duration.ofSeconds(5)).migrate());
        assertEquals(SchemaMigrator.BASELINE_VERSION, store.currentVersion(), "the tables were created with their version");

        final Migration addColumn = new Migration() {
            public int version() { return 2; }
            public String description() { return "add a column"; }
            public void apply() {
                database.write(connection -> Sql.update(connection, "ALTER TABLE users ADD COLUMN nickname TEXT"));
            }
        };
        assertEquals(2, new SchemaMigrator(new SqliteSchemaStore(database, List.of(addColumn)), List.of(addColumn),
                Duration.ofSeconds(5)).migrate());
        assertEquals(2, store.currentVersion());
    }

    @Test
    void aVersionTwoDatabaseGetsTheLeftoverReportColumnsAndKeepsItsJobs() throws Exception {
        final List<Migration> versionTwo = SqliteMigrations.all(database).subList(0, 1);
        assertEquals(2, new SchemaMigrator(new SqliteSchemaStore(database, versionTwo), versionTwo, Duration.ofSeconds(5)).migrate());
        database.write(connection -> Sql.update(connection,
                "INSERT INTO pending_documents (id, user_id, document_id, status) VALUES (?, ?, ?, ?)", "j1", "u1", "d1", "COMPLETE"));

        final List<Migration> migrations = SqliteMigrations.all(database);
        assertEquals(3, new SchemaMigrator(new SqliteSchemaStore(database, migrations), migrations, Duration.ofSeconds(5)).migrate());

        final String row = database.read(connection -> Sql.first(connection, "SELECT document_id, leftover_check_requested, "
                + "leftovers, email_report FROM pending_documents WHERE id = ?", r -> r.string("document_id") + ","
                + r.intValue("leftover_check_requested") + "," + r.string("leftovers") + "," + r.string("email_report"), "j1"));
        assertEquals("d1,0,null,null", row);

        // It can run again, as after stopping before its version was recorded.
        migrations.get(1).apply();
    }

    @Test
    void aVersionOneDatabaseGetsContextValuesAndKeepsItsContexts() {
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of());
        new SchemaMigrator(store, List.of(), Duration.ofSeconds(5)).migrate();
        database.write(connection -> Sql.update(connection, "INSERT INTO contexts (id, user_id, context_name) VALUES (?, ?, ?)",
                "c1", "u1", "claims"));

        final List<Migration> migrations = SqliteMigrations.all(database);
        assertEquals(3, new SchemaMigrator(new SqliteSchemaStore(database, migrations), migrations, Duration.ofSeconds(5)).migrate());

        final SqliteContextDataService contexts = new SqliteContextDataService(database, null, null, null);
        assertTrue(contexts.findOneByNameAndUserId("claims", "u1").getValues().isEmpty());
        assertEquals("-400", contexts.putValueIfAbsent("u1", "claims", "%00date-shift", "-400"));

        // Running it again, as after a stop before its version was recorded, changes nothing.
        assertDoesNotThrow(() -> migrations.get(0).apply());
        assertEquals("-400", contexts.findOneByNameAndUserId("claims", "u1").getValues().get("%00date-shift"));
    }

    @Test
    void anExistingDatabaseIsMigratedAndNewerOnesAreRefused() {
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of());
        new SchemaMigrator(store, List.of(), Duration.ofSeconds(5)).migrate();

        final Migration addColumn = new Migration() {
            public int version() { return 2; }
            public String description() { return "add a column"; }
            public void apply() {
                database.write(connection -> Sql.update(connection, "ALTER TABLE users ADD COLUMN nickname TEXT"));
            }
        };
        assertEquals(2, new SchemaMigrator(new SqliteSchemaStore(database, List.of(addColumn)), List.of(addColumn),
                Duration.ofSeconds(5)).migrate());
        assertEquals(2, store.currentVersion());

        // A release that knows only version 1 does not start on it.
        assertThrows(IllegalStateException.class, () -> new SchemaMigrator(store, List.of(), Duration.ofSeconds(5)).migrate());
    }

    @Test
    void theMigrationLockIsHeldByOneOwnerUntilReleased() {
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of());
        assertTrue(store.acquireLock("first", Duration.ofMinutes(1)));
        assertFalse(store.acquireLock("second", Duration.ofMinutes(1)));
        assertTrue(store.acquireLock("first", Duration.ofMinutes(1)), "the owner extends it");
        store.releaseLock("first");
        assertTrue(store.acquireLock("second", Duration.ofMillis(1)));
    }

    @Test
    void anExpiredMigrationLockCanBeTakenWithoutBeingReleased() throws Exception {
        final SqliteSchemaStore store = new SqliteSchemaStore(database, List.of());
        assertTrue(store.acquireLock("stopped", Duration.ofMillis(1)));
        Thread.sleep(20);
        // The instance that held it stopped without releasing it; its lease has run out.
        assertTrue(store.acquireLock("next", Duration.ofMinutes(1)));
        assertFalse(store.acquireLock("stopped", Duration.ofMinutes(1)), "the old holder no longer has it");
    }

}
