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

import ai.philterd.philter.data.mongodb.MongoImport;
import ai.philterd.philter.data.sqlite.SqliteExport;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves a SQLite database to MongoDB, for a deployment that grows from one Philter instance to several. Run with
 * Philter stopped.
 *
 * <p>Records are copied as stored, not through the services, so nothing is re-audited, re-signed, or renumbered:
 * every record keeps its id, timestamps, revisions, states, and signatures, and encrypted fields keep their
 * ciphertext, since both storages encrypt the same way under the same {@code PHILTER_ENCRYPTION_KEY}. The SQLite file
 * is opened read-only and is left as it was, so going back means continuing to use it.
 *
 * <p>The migration refuses, writing nothing, when a Philter is using the SQLite database, when either storage is not
 * at the schema version this Philter expects, or when the MongoDB database already holds Philter data. After
 * copying it verifies the copy: record counts, every ledger chain's hashes and signatures, the signing keys, and a
 * sample of each kind of encrypted field. It records itself in the audit log only when every check passes.
 *
 * <p>This is the one place that uses both storage implementations; the rest of Philter reaches storage only through
 * the interfaces in {@code data.services}.
 */
public final class StorageMigration {

    /** What a migration did: the records copied, by SQLite table, and what did not verify. */
    public record Result(Map<String, Long> copied, List<String> problems) {

        public boolean succeeded() {
            return problems.isEmpty();
        }

    }

    /** A precondition that failed before anything was written. */
    public static final class Refused extends RuntimeException {

        public Refused(final String message) {
            super(message);
        }

        public Refused(final String message, final Throwable cause) {
            super(message, cause);
        }

    }

    private StorageMigration() {
    }

    /**
     * Copies the SQLite database at {@code sqlitePath} to the MongoDB at {@code mongoDbConnectionString}, verifies the
     * copy, and records it in the audit log when it verifies.
     *
     * @throws Refused when a precondition fails, or anything fails before copying starts, such as reaching MongoDB;
     *                 nothing was written to MongoDB.
     */
    public static Result sqliteToMongoDb(final String sqlitePath, final String mongoDbConnectionString,
                                         final EncryptionService encryptionService, final Clock clock) {

        final SqliteExport source;
        try {
            source = SqliteExport.open(sqlitePath);
        } catch (final IllegalStateException ex) {
            throw new Refused(ex.getMessage(), ex);
        }

        // Until the copy starts nothing has been written, so a failure before it is a refusal, not a partial copy.
        boolean copying = false;
        try (source; MongoImport target = MongoImport.connect(mongoDbConnectionString, encryptionService)) {

            final Integer version = source.schemaVersion();
            if (version == null) {
                throw new Refused("The SQLite database at " + sqlitePath + " records no schema version; start Philter "
                        + "on it once with STORAGE_TYPE=sqlite to bring it up to date, stop it, and migrate again.");
            }
            if (version != source.expectedSchemaVersion()) {
                throw new Refused("The SQLite database is at schema version " + version + ", but this Philter expects "
                        + source.expectedSchemaVersion() + ". Migrate with the Philter release that last ran on it, or "
                        + "start this release on it once with STORAGE_TYPE=sqlite first.");
            }
            if (version != target.expectedSchemaVersion()) {
                throw new Refused("This Philter's SQLite storage is at schema version " + version + " but its MongoDB "
                        + "storage expects " + target.expectedSchemaVersion() + "; it cannot copy between them.");
            }
            if (source.heldGuards() > 0) {
                throw new Refused("A Philter stopped part way through an operation on the SQLite database and left its "
                        + "lock held. Start Philter on it once with STORAGE_TYPE=sqlite, which clears the lock and records "
                        + "that in the audit log, stop it, and migrate again.");
            }
            if (target.holdsPhilterData()) {
                throw new Refused("The MongoDB database already holds Philter data. The migration copies into an "
                        + "empty database only; it never merges.");
            }

            copying = true;
            final Map<String, Long> copied = new LinkedHashMap<>();
            for (final String table : SqliteExport.TABLES) {
                copied.put(table, target.copy(table, rows -> source.forEachRow(table, rows)));
            }
            target.recordSchemaVersion(version, clock.instant());

            final List<String> problems = new ArrayList<>();
            for (final String table : SqliteExport.TABLES) {
                final long expected = source.count(table);
                final long found = target.count(table);
                if (expected != found) {
                    problems.add("Table " + table + " has " + expected + " records in SQLite but " + found
                            + " were found in MongoDB.");
                }
            }
            problems.addAll(target.verify());

            if (problems.isEmpty()) {
                target.recordMigration(sqlitePath, copied);
            }
            return new Result(copied, problems);

        } catch (final Refused refused) {
            throw refused;
        } catch (final Exception ex) {
            if (!copying) {
                throw new Refused("The migration could not start: " + ex.getMessage(), ex);
            }
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex.getMessage(), ex);
        }

    }

}
