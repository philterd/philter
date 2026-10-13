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

import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.encryption.LocalEncryptionService;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.PrintStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code java -jar philter.jar migrate-storage --to mongodb}: moves the SQLite database at {@code SQLITE_PATH} to the
 * MongoDB at {@code MONGODB_CONNECTION_STRING}, with Philter stopped. See {@link StorageMigration}.
 */
public final class MigrateStorageCommand {

    /** The first argument that runs this command instead of starting Philter. */
    public static final String NAME = "migrate-storage";

    static final int SUCCEEDED = 0;
    static final int FAILED = 1;
    static final int USAGE = 2;

    private static final String USAGE_TEXT = """
            Usage: java -jar philter.jar migrate-storage --to mongodb

            Copies Philter's SQLite database to an empty MongoDB database, with Philter stopped, and verifies the copy.
            The SQLite database is not changed.

              SQLITE_PATH                 the SQLite database to copy (default: philter.db)
              MONGODB_CONNECTION_STRING   the MongoDB to copy it to (required)
              PHILTER_ENCRYPTION_KEY      the key Philter encrypts with, to verify encrypted fields (required)

            Afterwards, start Philter with STORAGE_TYPE=mongodb and the same PHILTER_ENCRYPTION_KEY.""";

    private MigrateStorageCommand() {
    }

    /** Runs the command and returns its exit status. */
    public static int run(final List<String> args, final Map<String, String> environment, final PrintStream out,
                          final PrintStream err) {
        // The command's own output says what it did; the MongoDB driver's connection messages would bury it.
        Configurator.setLevel("org.mongodb.driver", Level.WARN);
        return run(args, environment, out, err, LocalEncryptionService::new, Clock.systemUTC());
    }

    static int run(final List<String> args, final Map<String, String> environment, final PrintStream out,
                   final PrintStream err, final Supplier<EncryptionService> encryption, final Clock clock) {

        if (!List.of("--to", "mongodb").equals(args)) {
            err.println(args.isEmpty() || args.equals(List.of("--help")) ? USAGE_TEXT
                    : "Only --to mongodb is supported.\n\n" + USAGE_TEXT);
            return USAGE;
        }
        final String connectionString = environment.get("MONGODB_CONNECTION_STRING");
        if (connectionString == null || connectionString.isBlank()) {
            err.println("MONGODB_CONNECTION_STRING is required: the MongoDB to copy the SQLite database to.\n\n" + USAGE_TEXT);
            return USAGE;
        }
        final String sqlitePath = environment.getOrDefault("SQLITE_PATH", "philter.db");

        final EncryptionService encryptionService;
        try {
            encryptionService = encryption.get();
        } catch (final IllegalStateException ex) {
            err.println(ex.getMessage());
            return USAGE;
        }

        out.println("Migrating the SQLite database at " + sqlitePath + " to MongoDB.");
        final StorageMigration.Result result;
        try {
            result = StorageMigration.sqliteToMongoDb(sqlitePath, connectionString, encryptionService, clock);
        } catch (final StorageMigration.Refused refused) {
            err.println("Nothing was migrated: " + refused.getMessage());
            return FAILED;
        } catch (final RuntimeException ex) {
            err.println("The migration stopped: " + ex.getMessage());
            err.println("MongoDB may hold part of the data. Drop its philter database before migrating again; the "
                    + "SQLite database is unchanged.");
            return FAILED;
        }

        result.copied().forEach((table, count) -> out.println("  " + table + ": " + count));
        if (!result.succeeded()) {
            err.println("The copy did not verify:");
            result.problems().forEach(problem -> err.println("  " + problem));
            err.println("Do not start Philter on this MongoDB. Drop its philter database before migrating again; the "
                    + "SQLite database is unchanged.");
            return FAILED;
        }
        out.println("Migrated and verified. Start Philter with STORAGE_TYPE=mongodb and the same "
                + "PHILTER_ENCRYPTION_KEY. Keep the SQLite database until you have confirmed Philter works on MongoDB.");
        return SUCCEEDED;

    }

}
