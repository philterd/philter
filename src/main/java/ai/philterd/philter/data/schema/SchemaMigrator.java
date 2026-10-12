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
package ai.philterd.philter.data.schema;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Brings Philter's data to the layout this release expects before Philter starts, whatever the storage.
 *
 * <ul>
 *   <li>At the expected version: nothing to do.</li>
 *   <li>At an older version: runs each pending migration in order, recording each as it completes.</li>
 *   <li>At a newer version, written by a newer Philter: refuses to start rather than risk the data.</li>
 *   <li>With no version: a fresh database is recorded at the expected version; one that already holds data was
 *   written before versions were recorded, so it is at version 1 and is migrated from there.</li>
 * </ul>
 *
 * <p>When several instances start together, one takes the lock and migrates while the others wait for it. A
 * failed migration stops startup and leaves the version at the last migration that completed, so a fixed release
 * can carry on from there.
 */
public final class SchemaMigrator {

    private static final Logger LOGGER = LogManager.getLogger(SchemaMigrator.class);

    /** The version of the layout Philter 4.0 shipped with, before any migration. */
    public static final int BASELINE_VERSION = 1;

    /** How long a lock lasts without being renewed; it is renewed before each migration. */
    static final Duration LOCK_LEASE = Duration.ofMinutes(10);

    private final SchemaStore store;
    private final List<Migration> migrations;
    private final Duration waitLimit;
    private final Duration pollInterval;
    private final Clock clock;

    public SchemaMigrator(final SchemaStore store, final List<Migration> migrations, final Duration waitLimit) {
        this(store, migrations, waitLimit, Duration.ofSeconds(1), Clock.systemUTC());
    }

    SchemaMigrator(final SchemaStore store, final List<Migration> migrations, final Duration waitLimit,
                   final Duration pollInterval, final Clock clock) {
        this.store = store;
        this.migrations = ordered(migrations);
        this.waitLimit = waitLimit;
        this.pollInterval = pollInterval;
        this.clock = clock;
    }

    /** The version this release expects: the last migration's, or the baseline when there are none. */
    public int expectedVersion() {
        return migrations.isEmpty() ? BASELINE_VERSION : migrations.get(migrations.size() - 1).version();
    }

    /**
     * Brings the data to {@link #expectedVersion()}, waiting for another instance that is doing so.
     *
     * @return The version the data is at.
     * @throws IllegalStateException when the data is newer than this release, a migration fails, or another
     *                               instance holds the lock for longer than the wait limit.
     */
    public int migrate() {

        final int expected = expectedVersion();
        final Integer found = store.currentVersion();
        LOGGER.info("Database schema version: {} (this release expects {}).", found == null ? "none" : found, expected);
        refuseNewer(found, expected);
        if (found != null && found == expected) {
            return expected;
        }

        final String owner = UUID.randomUUID().toString();
        final Instant giveUp = clock.instant().plus(waitLimit);
        while (!store.acquireLock(owner, LOCK_LEASE)) {
            // Another instance is migrating; it may finish while this one waits.
            final Integer now = store.currentVersion();
            refuseNewer(now, expected);
            if (now != null && now == expected) {
                LOGGER.info("Another instance brought the database schema to version {}.", expected);
                return expected;
            }
            if (clock.instant().isAfter(giveUp)) {
                throw new IllegalStateException("Another Philter instance has held the database schema lock for longer "
                        + "than " + waitLimit.toSeconds() + " seconds while migrating to version " + expected
                        + ". Check that instance's log, then restart.");
            }
            sleep();
        }

        try {
            return migrateHoldingLock(owner, expected);
        } finally {
            store.releaseLock(owner);
        }

    }

    private int migrateHoldingLock(final String owner, final int expected) {

        // Read again: another instance may have migrated before this one took the lock.
        Integer version = store.currentVersion();
        refuseNewer(version, expected);
        if (version == null) {
            if (!store.hasData()) {
                store.initialize(expected, clock.instant());
                LOGGER.info("Recorded database schema version {} for a new database.", expected);
                return expected;
            }
            version = BASELINE_VERSION;
            LOGGER.info("The database holds data but no schema version, so it is at version {}.", BASELINE_VERSION);
            if (expected == BASELINE_VERSION) {
                store.initialize(BASELINE_VERSION, clock.instant());
                return BASELINE_VERSION;
            }
        }

        for (final Migration migration : migrations) {
            if (migration.version() <= version) {
                continue;
            }
            store.acquireLock(owner, LOCK_LEASE);
            LOGGER.info("Running database schema migration {}: {}", migration.version(), migration.description());
            try {
                migration.apply();
            } catch (final Exception ex) {
                LOGGER.error("Database schema migration {} ({}) failed; the schema stays at version {}.",
                        migration.version(), migration.description(), version, ex);
                throw new IllegalStateException("Database schema migration " + migration.version() + " ("
                        + migration.description() + ") failed: " + ex.getMessage() + ". The schema stays at version "
                        + version + ".", ex);
            }
            store.recordMigration(migration.version(), migration.description(), clock.instant());
            version = migration.version();
            LOGGER.info("Database schema migration {} completed.", migration.version());
        }
        return version;

    }

    private static void refuseNewer(final Integer found, final int expected) {
        if (found != null && found > expected) {
            throw new IllegalStateException("The database schema is at version " + found + ", but this Philter "
                    + "expects version " + expected + ". It was written by a newer Philter; run that release or a "
                    + "later one. Philter does not start, so the data is not changed.");
        }
    }

    /** The migrations by version, checked to run from version 2 up with no gaps or repeats. */
    private static List<Migration> ordered(final List<Migration> migrations) {
        final List<Migration> sorted = new ArrayList<>(migrations);
        sorted.sort(Comparator.comparingInt(Migration::version));
        int previous = BASELINE_VERSION;
        for (final Migration migration : sorted) {
            if (migration.version() != previous + 1) {
                throw new IllegalArgumentException("Schema migrations must be numbered from " + (BASELINE_VERSION + 1)
                        + " with no gaps or repeats; found " + migration.version() + " after " + previous + ".");
            }
            previous = migration.version();
        }
        return List.copyOf(sorted);
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the database schema lock.", ex);
        }
    }

}
