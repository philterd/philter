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

import ai.philterd.philter.data.schema.SchemaMigrator;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads everything a SQLite database holds, for copying it to other storage, without changing the database file. It
 * holds the database's {@link SqliteInstanceLock} while open, so no Philter can use the database meanwhile, and opens
 * the file read-only.
 *
 * <p>Rows are handed over as plain values, so the reader of a row needs nothing from SQLite: each column's value is
 * a {@code Long}, {@code Double}, {@code String}, {@code byte[]}, or null. Rows come in the order they were written.
 */
public final class SqliteExport implements AutoCloseable {

    /**
     * Every table holding Philter's data, in the order to copy them. Left out: the schema version, its history and
     * lock, which the target records itself, and the queue admission and evidence guards, which hold nothing once
     * Philter has stopped.
     */
    public static final List<String> TABLES = List.of(
            "admin_settings", "users", "api_keys", "sign_in_challenges", "signing_keys", "signing_key_state",
            "policies", "policy_revision_counters", "policy_versions", "policy_contents", "policy_deletions",
            "custom_lists", "redact_lists", "contexts", "context_entries", "vectors", "legal_holds", "ledger",
            "ledger_chains", "pending_documents", "queue_scheduling", "webhook_deliveries",
            "audit_events", "pii_count_aggregates", "pii_counts");

    private final SqliteInstanceLock lock;
    private final SqliteDatabase database;

    private SqliteExport(final SqliteInstanceLock lock, final SqliteDatabase database) {
        this.lock = lock;
        this.database = database;
    }

    /**
     * Takes the database's instance lock and opens it read-only.
     *
     * @throws IllegalStateException when a Philter is using the database, or there is no database at {@code path}.
     */
    public static SqliteExport open(final String path) {
        // Checked before the lock, which would otherwise leave a lock file beside a database that is not there.
        if (!java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path))) {
            throw new IllegalStateException("There is no SQLite database at " + path + ".");
        }
        final SqliteInstanceLock lock = SqliteInstanceLock.acquire(path);
        try {
            return new SqliteExport(lock, SqliteDatabase.openReadOnly(path));
        } catch (final RuntimeException ex) {
            try {
                lock.close();
            } catch (final Exception closing) {
                ex.addSuppressed(closing);
            }
            throw ex;
        }
    }

    /** The database file. */
    public String path() {
        return database.path();
    }

    /** The schema version the database records, or null when it records none. */
    public Integer schemaVersion() {
        return database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM sqlite_master WHERE type = 'table' AND name = 'schema_version'") == 0
                ? null
                : Sql.first(connection, "SELECT version FROM schema_version WHERE id = 1", row -> row.intValue("version")));
    }

    /** The schema version this Philter's SQLite storage expects. */
    public int expectedSchemaVersion() {
        return SchemaMigrator.expectedVersion(SqliteMigrations.all(database));
    }

    /**
     * The guards a Philter left held when it stopped part way through an operation: queue admission and evidence
     * guards. SQLite storage clears and audits them when it next starts, so a migration leaves them for that.
     */
    public long heldGuards() {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM queue_admission WHERE token IS NOT NULL")
                + Sql.count(connection, "SELECT COUNT(*) AS n FROM evidence_guards WHERE token IS NOT NULL"));
    }

    /** How many rows the table holds. */
    public long count(final String table) {
        requireKnown(table);
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM " + table));
    }

    /** Hands each of the table's rows to {@code rows}, in the order they were written, as column name to value. */
    public void forEachRow(final String table, final Consumer<Map<String, Object>> rows) {
        requireKnown(table);
        database.read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + table + " ORDER BY rowid");
                 ResultSet results = statement.executeQuery()) {
                final ResultSetMetaData columns = results.getMetaData();
                while (results.next()) {
                    final Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= columns.getColumnCount(); column++) {
                        row.put(columns.getColumnName(column), value(results.getObject(column)));
                    }
                    rows.accept(row);
                }
            }
            return null;
        });
    }

    private static Object value(final Object stored) throws SQLException {
        if (stored instanceof Integer || stored instanceof Short || stored instanceof Byte) {
            return ((Number) stored).longValue();
        }
        return stored;
    }

    private static void requireKnown(final String table) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Not a table of Philter's data: " + table);
        }
    }

    @Override
    public void close() throws Exception {
        try {
            database.close();
        } finally {
            lock.close();
        }
    }

}
