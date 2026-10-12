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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {

    /** A schema store in memory, shared by every instance that uses it, as a database is. */
    static final class MemoryStore implements SchemaStore {

        Integer version;
        boolean data;
        String lockOwner;
        Instant lockExpires;
        final List<String> history = new ArrayList<>();
        int initialized;

        @Override
        public synchronized Integer currentVersion() {
            return version;
        }

        @Override
        public synchronized boolean hasData() {
            return data;
        }

        @Override
        public synchronized void initialize(final int version, final Instant at) {
            this.version = version;
            initialized++;
        }

        @Override
        public synchronized void recordMigration(final int version, final String description, final Instant ranAt) {
            this.version = version;
            history.add(version + " " + description);
        }

        @Override
        public synchronized boolean acquireLock(final String owner, final Duration lease) {
            final Instant now = Instant.now();
            if (lockOwner != null && !lockOwner.equals(owner) && lockExpires.isAfter(now)) {
                return false;
            }
            lockOwner = owner;
            lockExpires = now.plus(lease);
            return true;
        }

        @Override
        public synchronized void releaseLock(final String owner) {
            if (owner.equals(lockOwner)) {
                lockOwner = null;
            }
        }
    }

    /** A migration that records each run. */
    private static Migration migration(final int version, final List<Integer> runs) {
        return new Migration() {
            @Override
            public int version() {
                return version;
            }

            @Override
            public String description() {
                return "step " + version;
            }

            @Override
            public void apply() {
                runs.add(version);
            }
        };
    }

    private static SchemaMigrator migrator(final SchemaStore store, final List<Migration> migrations) {
        return new SchemaMigrator(store, migrations, Duration.ofSeconds(5), Duration.ofMillis(10), Clock.systemUTC());
    }

    @Test
    void aFreshDatabaseIsRecordedAtTheExpectedVersionWithoutMigrating() {
        final MemoryStore store = new MemoryStore();
        final List<Integer> runs = new ArrayList<>();
        assertEquals(3, migrator(store, List.of(migration(2, runs), migration(3, runs))).migrate());
        assertEquals(3, store.version);
        assertEquals(List.of(), runs);
        assertEquals(1, store.initialized);
        assertNull(store.lockOwner);
    }

    @Test
    void aFreshDatabaseWithNoMigrationsIsAtTheBaseline() {
        final MemoryStore store = new MemoryStore();
        assertEquals(SchemaMigrator.BASELINE_VERSION, migrator(store, List.of()).migrate());
        assertEquals(SchemaMigrator.BASELINE_VERSION, store.version);
    }

    @Test
    void anOlderDatabaseIsMigratedInOrderAndEachStepRecorded() {
        final MemoryStore store = new MemoryStore();
        store.version = 1;
        final List<Integer> runs = new ArrayList<>();
        assertEquals(4, migrator(store, List.of(migration(4, runs), migration(2, runs), migration(3, runs))).migrate());
        assertEquals(List.of(2, 3, 4), runs);
        assertEquals(List.of("2 step 2", "3 step 3", "4 step 4"), store.history);
        assertEquals(4, store.version);
        assertNull(store.lockOwner);
    }

    @Test
    void onlyThePendingMigrationsRun() {
        final MemoryStore store = new MemoryStore();
        store.version = 3;
        final List<Integer> runs = new ArrayList<>();
        migrator(store, List.of(migration(2, runs), migration(3, runs), migration(4, runs))).migrate();
        assertEquals(List.of(4), runs);
    }

    @Test
    void dataWrittenBeforeVersionsIsMigratedFromTheBaseline() {
        final MemoryStore store = new MemoryStore();
        store.data = true;
        final List<Integer> runs = new ArrayList<>();
        assertEquals(3, migrator(store, List.of(migration(2, runs), migration(3, runs))).migrate());
        assertEquals(List.of(2, 3), runs);
        assertEquals(0, store.initialized);
    }

    @Test
    void dataWrittenBeforeVersionsWithNoMigrationsIsRecordedAtTheBaseline() {
        final MemoryStore store = new MemoryStore();
        store.data = true;
        assertEquals(1, migrator(store, List.of()).migrate());
        assertEquals(1, store.version);
    }

    @Test
    void aNewerDatabaseStopsStartupNamingBothVersions() {
        final MemoryStore store = new MemoryStore();
        store.version = 5;
        final List<Integer> runs = new ArrayList<>();
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> migrator(store, List.of(migration(2, runs), migration(3, runs))).migrate());
        assertTrue(refused.getMessage().contains("version 5"), refused.getMessage());
        assertTrue(refused.getMessage().contains("expects version 3"), refused.getMessage());
        assertEquals(5, store.version);
        assertEquals(List.of(), runs);
    }

    @Test
    void aFailedMigrationStopsStartupAndLeavesTheLastCompletedVersion() {
        final MemoryStore store = new MemoryStore();
        store.version = 1;
        final List<Integer> runs = new ArrayList<>();
        final Migration failing = new Migration() {
            @Override
            public int version() {
                return 3;
            }

            @Override
            public String description() {
                return "split names";
            }

            @Override
            public void apply() {
                throw new IllegalArgumentException("bad record");
            }
        };
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> migrator(store, List.of(migration(2, runs), failing, migration(4, runs))).migrate());
        assertTrue(refused.getMessage().contains("migration 3 (split names) failed: bad record"), refused.getMessage());
        assertEquals(2, store.version);
        assertEquals(List.of(2), runs);
        // The lock is released, so a fixed release can carry on from version 2.
        assertNull(store.lockOwner);
        final List<Integer> retried = new ArrayList<>();
        assertEquals(4, migrator(store, List.of(migration(2, retried), migration(3, retried), migration(4, retried))).migrate());
        assertEquals(List.of(3, 4), retried);
    }

    @Test
    void instancesStartingTogetherMigrateOnce() throws Exception {
        final MemoryStore store = new MemoryStore();
        store.version = 1;
        final List<Integer> runs = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);
        final Migration slow = new Migration() {
            @Override
            public int version() {
                return 2;
            }

            @Override
            public String description() {
                return "slow";
            }

            @Override
            public void apply() throws InterruptedException {
                runs.add(2);
                Thread.sleep(200);
            }
        };
        final ExecutorService instances = Executors.newFixedThreadPool(4);
        try {
            final List<Future<Integer>> versions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                versions.add(instances.submit(() -> {
                    start.await();
                    return migrator(store, List.of(slow)).migrate();
                }));
            }
            start.countDown();
            for (final Future<Integer> version : versions) {
                assertEquals(2, version.get(10, TimeUnit.SECONDS));
            }
        } finally {
            instances.shutdownNow();
        }
        assertEquals(List.of(2), runs);
        assertEquals(List.of("2 slow"), store.history);
    }

    @Test
    void anInstanceGivesUpWhenAnotherHoldsTheLockTooLong() {
        final MemoryStore store = new MemoryStore();
        store.version = 1;
        store.acquireLock("another instance", Duration.ofMinutes(10));
        final SchemaMigrator waiting = new SchemaMigrator(store, List.of(migration(2, new ArrayList<>())),
                Duration.ofMillis(50), Duration.ofMillis(10), Clock.systemUTC());
        final IllegalStateException refused = assertThrows(IllegalStateException.class, waiting::migrate);
        assertTrue(refused.getMessage().contains("schema lock"), refused.getMessage());
        assertEquals(1, store.version);
    }

    @Test
    void migrationsMustBeNumberedFromTwoWithoutGaps() {
        final List<Integer> runs = new ArrayList<>();
        assertThrows(IllegalArgumentException.class, () -> migrator(new MemoryStore(), List.of(migration(3, runs))));
        assertThrows(IllegalArgumentException.class,
                () -> migrator(new MemoryStore(), List.of(migration(2, runs), migration(2, runs))));
        assertFalse(runs.contains(2));
    }

}
