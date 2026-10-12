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
import ai.philterd.philter.data.schema.SchemaStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The schema version of a SQLite database, its migration history, and the migration lock. A new database is
 * created at the expected version: {@link #initialize} creates the version 1 tables and runs every migration up
 * to the version it records, so the tables always match the recorded version.
 */
public class SqliteSchemaStore implements SchemaStore {

    private final SqliteDatabase database;
    private final List<Migration> migrations;

    public SqliteSchemaStore(final SqliteDatabase database, final List<Migration> migrations) {
        this.database = database;
        this.migrations = migrations;
        database.write(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version (id INTEGER PRIMARY KEY CHECK (id = 1), "
                        + "version INTEGER NOT NULL, initialized_at INTEGER, updated_at INTEGER)");
                statement.execute("CREATE TABLE IF NOT EXISTS schema_history (version INTEGER PRIMARY KEY, "
                        + "description TEXT, ran_at INTEGER)");
                statement.execute("CREATE TABLE IF NOT EXISTS schema_lock (id INTEGER PRIMARY KEY CHECK (id = 1), "
                        + "owner TEXT NOT NULL, acquired_at INTEGER, expires_at INTEGER NOT NULL)");
            }
            return null;
        });
    }

    @Override
    public Integer currentVersion() {
        return database.read(connection -> Sql.first(connection, "SELECT version FROM schema_version WHERE id = 1",
                row -> row.intValue("version")));
    }

    @Override
    public boolean hasData() {
        // Any of Philter's tables means the database was written before versions were recorded.
        return database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'schema_%' "
                        + "AND name NOT LIKE 'sqlite_%'") > 0);
    }

    @Override
    public void initialize(final int version, final Instant at) {
        // The version 1 tables and their version are created in one transaction, so a database is never left with
        // tables but no version, which would stop every later start. Each migration's version is recorded once it
        // has run, so a start that stops part way resumes from there.
        database.write(connection -> {
            try (Statement statement = connection.createStatement()) {
                for (final String ddl : SqliteSchema.BASELINE) {
                    statement.execute(ddl);
                }
            }
            return writeVersion(connection, SchemaMigrator.BASELINE_VERSION, at);
        });
        for (final Migration migration : migrations) {
            if (migration.version() <= version) {
                try {
                    migration.apply();
                } catch (final Exception ex) {
                    throw new IllegalStateException("Creating the database failed at migration " + migration.version()
                            + ": " + ex.getMessage(), ex);
                }
                database.write(connection -> writeVersion(connection, migration.version(), at));
            }
        }
    }

    private static int writeVersion(final Connection connection, final int version, final Instant at) throws SQLException {
        return Sql.update(connection, "INSERT INTO schema_version (id, version, initialized_at, updated_at) VALUES (1, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET version = excluded.version, updated_at = excluded.updated_at",
                version, at.toEpochMilli(), at.toEpochMilli());
    }

    @Override
    public void recordMigration(final int version, final String description, final Instant ranAt) {
        // One transaction, so the version never moves without its history entry, or the reverse.
        database.write(connection -> {
            Sql.update(connection, "INSERT INTO schema_history (version, description, ran_at) VALUES (?, ?, ?)",
                    version, description, ranAt.toEpochMilli());
            return writeVersion(connection, version, ranAt);
        });
    }

    @Override
    public boolean acquireLock(final String owner, final Duration lease) {
        final long now = System.currentTimeMillis();
        return database.write(connection -> Sql.update(connection,
                "INSERT INTO schema_lock (id, owner, acquired_at, expires_at) VALUES (1, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET owner = excluded.owner, acquired_at = excluded.acquired_at, "
                        + "expires_at = excluded.expires_at WHERE schema_lock.owner = excluded.owner OR schema_lock.expires_at < ?",
                owner, now, now + lease.toMillis(), now) == 1);
    }

    @Override
    public void releaseLock(final String owner) {
        database.write(connection -> Sql.update(connection, "DELETE FROM schema_lock WHERE id = 1 AND owner = ?", owner));
    }

}
