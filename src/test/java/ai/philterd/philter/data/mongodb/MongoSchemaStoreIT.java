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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.schema.Migration;
import ai.philterd.philter.data.schema.SchemaMigrator;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The schema version and its lock, kept in MongoDB, under the startup cases the migrator handles. */
class MongoSchemaStoreIT extends AbstractMongoIT {

    private Migration migration(final int version, final List<Integer> runs) {
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
                mongoClient.getDatabase("philter").getCollection("probe").insertOne(new Document("step", version));
            }
        };
    }

    private SchemaMigrator migrator(final List<Migration> migrations) {
        return new SchemaMigrator(new MongoSchemaStore(mongoClient), migrations, Duration.ofSeconds(10));
    }

    private Document version() {
        return mongoClient.getDatabase("philter").getCollection(MongoSchemaStore.COLLECTION)
                .find(new Document("_id", "version")).first();
    }

    @Test
    void aFreshDatabaseIsRecordedAtTheCurrentVersion() {
        final List<Integer> runs = new ArrayList<>();
        assertEquals(3, migrator(List.of(migration(2, runs), migration(3, runs))).migrate());
        assertEquals(List.of(), runs);
        assertEquals(3, version().getInteger("version"));
        assertNotNull(version().getDate("initialized_at"));
        assertNull(mongoClient.getDatabase("philter").getCollection(MongoSchemaStore.COLLECTION)
                .find(new Document("_id", "lock")).first(), "the lock is released");
    }

    @Test
    void theShippedReleaseRecordsTheBaseline() {
        assertEquals(SchemaMigrator.BASELINE_VERSION, migrator(MongoMigrations.all(mongoClient)).migrate());
        assertEquals(SchemaMigrator.BASELINE_VERSION, version().getInteger("version"));
    }

    @Test
    void anOlderDatabaseIsMigratedAndEachStepRecordedWithWhenItRan() {
        new MongoSchemaStore(mongoClient).initialize(1, java.time.Instant.now());
        final List<Integer> runs = new ArrayList<>();
        assertEquals(3, migrator(List.of(migration(2, runs), migration(3, runs))).migrate());
        assertEquals(List.of(2, 3), runs);
        final Document version = version();
        assertEquals(3, version.getInteger("version"));
        final List<Document> history = version.getList("history", Document.class);
        assertEquals(List.of(2, 3), history.stream().map(entry -> entry.getInteger("version")).toList());
        assertEquals("step 3", history.get(1).getString("description"));
        assertNotNull(history.get(1).getDate("ran_at"));
    }

    @Test
    void existingDataWithoutAVersionIsMigratedFromTheBaseline() {
        mongoClient.getDatabase("philter").getCollection("policies").insertOne(new Document("name", "default"));
        final List<Integer> runs = new ArrayList<>();
        assertEquals(2, migrator(List.of(migration(2, runs))).migrate());
        assertEquals(List.of(2), runs);
    }

    @Test
    void aNewerDatabaseStopsStartup() {
        new MongoSchemaStore(mongoClient).initialize(7, java.time.Instant.now());
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> migrator(List.of(migration(2, new ArrayList<>()))).migrate());
        assertTrue(refused.getMessage().contains("version 7"), refused.getMessage());
        assertTrue(refused.getMessage().contains("expects version 2"), refused.getMessage());
        assertEquals(7, version().getInteger("version"));
    }

    @Test
    void aFailedMigrationLeavesTheLastCompletedVersion() {
        new MongoSchemaStore(mongoClient).initialize(1, java.time.Instant.now());
        final List<Integer> runs = new ArrayList<>();
        final Migration failing = new Migration() {
            @Override
            public int version() {
                return 3;
            }

            @Override
            public String description() {
                return "fails";
            }

            @Override
            public void apply() {
                throw new IllegalStateException("no space");
            }
        };
        assertThrows(IllegalStateException.class, () -> migrator(List.of(migration(2, runs), failing)).migrate());
        assertEquals(2, version().getInteger("version"));
        assertEquals(1, version().getList("history", Document.class).size());
    }

    @Test
    void theLockIsHeldByOneOwnerUntilItExpires() {
        final MongoSchemaStore store = new MongoSchemaStore(mongoClient);
        assertTrue(store.acquireLock("a", Duration.ofMinutes(1)));
        assertFalse(store.acquireLock("b", Duration.ofMinutes(1)));
        assertTrue(store.acquireLock("a", Duration.ofMinutes(1)), "the owner may extend it");
        store.releaseLock("b");
        assertFalse(store.acquireLock("b", Duration.ofMinutes(1)), "only the owner releases it");
        store.releaseLock("a");
        assertTrue(store.acquireLock("b", Duration.ofMillis(1)));
        sleep(20);
        assertTrue(store.acquireLock("c", Duration.ofMinutes(1)), "an expired lock is free");
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void instancesStartingTogetherMigrateOnce() throws Exception {
        new MongoSchemaStore(mongoClient).initialize(1, java.time.Instant.now());
        final List<Integer> runs = new CopyOnWriteArrayList<>();
        final CountDownLatch start = new CountDownLatch(1);
        final ExecutorService instances = Executors.newFixedThreadPool(4);
        try {
            final List<Future<Integer>> versions = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                versions.add(instances.submit(() -> {
                    start.await();
                    return migrator(List.of(migration(2, runs), migration(3, runs))).migrate();
                }));
            }
            start.countDown();
            for (final Future<Integer> version : versions) {
                assertEquals(3, version.get(30, TimeUnit.SECONDS));
            }
        } finally {
            instances.shutdownNow();
        }
        assertEquals(List.of(2, 3), runs);
        assertEquals(2, mongoClient.getDatabase("philter").getCollection("probe").countDocuments());
        assertEquals(2, version().getList("history", Document.class).size());
    }

}
