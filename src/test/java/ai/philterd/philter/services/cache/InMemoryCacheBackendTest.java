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
package ai.philterd.philter.services.cache;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InMemoryCacheBackendTest {

    private final CacheBackend backend = InMemoryCacheBackend.INSTANCE;

    @Test
    public void stringSetGetExistsDelete() {
        final String key = "string-key-" + System.nanoTime();

        assertNull(backend.get(key));
        assertFalse(backend.exists(key));

        backend.setex(key, 60, "value");
        assertEquals("value", backend.get(key));
        assertTrue(backend.exists(key));

        backend.del(key);
        assertNull(backend.get(key));
        assertFalse(backend.exists(key));
    }

    @Test
    public void hashSetGetExistsDelete() {
        final String key = "hash-key-" + System.nanoTime();

        assertNull(backend.hget(key, "field"));
        assertFalse(backend.hexists(key, "field"));

        backend.hset(key, "field", "value");
        backend.expire(key, 60);

        assertEquals("value", backend.hget(key, "field"));
        assertTrue(backend.hexists(key, "field"));
        assertFalse(backend.hexists(key, "missing"));

        backend.del(key);
        assertNull(backend.hget(key, "field"));
        assertFalse(backend.hexists(key, "field"));
    }

    @Test
    void cleanupReclaimsOneTimeStringsCountersAndWholeHashes() throws Exception {
        final AtomicLong now = new AtomicLong(1000);
        final var cache = new InMemoryCacheBackend(now::get);
        for (int i = 0; i < 2000; i++) {
            cache.setex("token-" + i, 1, "value");
            cache.incrementAndExpire("login-" + i, 1);
            cache.hset("context-" + i, "field", "value");
            cache.expire("context-" + i, 1);
        }
        cache.setex("permanent", 0, "value");
        cache.hset("permanent-hash", "field", "value");
        now.set(2000);
        cache.removeExpiredEntries();
        assertEquals(1, storedEntries(cache, "strings").size());
        assertEquals(1, storedEntries(cache, "hashes").size());
        assertEquals("value", cache.get("permanent"));
        assertEquals("value", cache.hget("permanent-hash", "field"));
    }

    @Test
    void cleanupPreservesRefreshedValuesAndSlidingCounterExpiry() throws Exception {
        final AtomicLong now = new AtomicLong(1000);
        final var cache = new InMemoryCacheBackend(now::get);
        cache.setex("string", 1, "old");
        cache.incrementAndExpire("counter", 1);
        cache.hset("hash", "field", "value");
        cache.expire("hash", 1);
        now.set(1500);
        cache.setex("string", 2, "new");
        assertEquals(2, cache.incrementAndExpire("counter", 2));
        cache.expire("hash", 2);
        now.set(2000);
        cache.removeExpiredEntries();
        assertEquals("new", cache.get("string"));
        assertEquals("2", cache.get("counter"));
        assertEquals("value", cache.hget("hash", "field"));
        now.set(3500);
        cache.removeExpiredEntries();
        assertTrue(storedEntries(cache, "strings").isEmpty());
        assertTrue(storedEntries(cache, "hashes").isEmpty());
        assertEquals(1, cache.incrementAndExpire("counter", 1));
    }

    @Test
    void expiredReadersCannotRemoveConcurrentReplacements() throws Exception {
        assertExpiredReaderPreservesReplacement(false);
        assertExpiredReaderPreservesReplacement(true);
    }

    private void assertExpiredReaderPreservesReplacement(final boolean hash) throws Exception {
        final AtomicLong now = new AtomicLong(1000);
        final CountDownLatch observed = new CountDownLatch(1);
        final CountDownLatch replaced = new CountDownLatch(1);
        final var cache = new InMemoryCacheBackend(() -> {
            final long time = now.get();
            if (Thread.currentThread().getName().equals("expired-reader")) {
                observed.countDown();
                try {
                    if (!replaced.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Replacement did not complete");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            return time;
        });
        if (hash) {
            cache.hset("key", "field", "old");
            cache.expire("key", 1);
        } else {
            cache.setex("key", 1, "old");
        }
        now.set(2000);
        try (var executor = Executors.newSingleThreadExecutor(task -> new Thread(task, "expired-reader"))) {
            final var read = executor.submit(() -> hash ? cache.hget("key", "field") : cache.get("key"));
            try {
                assertTrue(observed.await(5, TimeUnit.SECONDS));
                if (hash) {
                    cache.hset("key", "field", "new");
                    cache.expire("key", 60);
                } else {
                    cache.setex("key", 60, "new");
                }
            } finally {
                replaced.countDown();
            }
            assertNull(read.get(5, TimeUnit.SECONDS));
            assertEquals("new", hash ? cache.hget("key", "field") : cache.get("key"));
        }
    }

    @Test
    void concurrentCounterIncrementsRemainAtomicDuringCleanup() throws Exception {
        final var cache = new InMemoryCacheBackend(() -> 1000L);
        try (var executor = Executors.newFixedThreadPool(8)) {
            final var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int worker = 0; worker < 8; worker++) {
                tasks.add(executor.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        cache.incrementAndExpire("counter", 60);
                        cache.removeExpiredEntries();
                    }
                }));
            }
            for (var task : tasks) {
                task.get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals("4000", cache.get("counter"));
    }

    @Test
    void deletingLastHashFieldReclaimsContainer() throws Exception {
        final var cache = new InMemoryCacheBackend(() -> 1000L);
        cache.hset("hash", "first", "one");
        cache.hset("hash", "second", "two");
        cache.hdel("hash", "first");
        assertEquals("two", cache.hget("hash", "second"));
        cache.hdel("hash", "second");
        assertTrue(storedEntries(cache, "hashes").isEmpty());
    }

    @Test
    void closingBorrowerDoesNotStopSingletonCleanup() throws Exception {
        final var cache = InMemoryCacheBackend.INSTANCE;
        final String key = "idle-hash-" + System.nanoTime();
        cache.hset(key, "field", "value");
        cache.expire(key, 1);
        cache.close();
        final Map<?, ?> hashes = storedEntries(cache, "hashes");
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hashes.containsKey(key) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(hashes.containsKey(key));
    }

    private static Map<?, ?> storedEntries(final InMemoryCacheBackend cache, final String name) throws Exception {
        final var field = InMemoryCacheBackend.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(cache);
    }

    @Test
    void capacityBoundsStringsHashFieldsAndPayloadSize() throws Exception {
        var cache = new InMemoryCacheBackend(() -> 1000L, 3, 4096);
        cache.setex("one", 0, "one");
        cache.hset("hash", "field", "value");
        for (int i = 0; i < 1000; i++) {
            cache.setex("more-" + i, 0, "value");
            cache.hset("hash", "more-" + i, "value");
        }
        assertEquals(1, storedEntries(cache, "strings").size());
        assertNull(cache.hget("hash", "more-0"));
        cache.hset("hash", "field", "x".repeat(4096));
        assertNull(cache.hget("hash", "field"), "Rejected refresh must discard the stale value");
        cache.setex("one", 0, "x".repeat(4096));
        assertNull(cache.get("one"));
    }

    @Test
    void counterOverflowPreservesExistingLockoutsAndBlocksNewLoginsUntilWindowEnds() throws Exception {
        final AtomicLong now = new AtomicLong(1000);
        var cache = new InMemoryCacheBackend(now::get, 1, 4096);
        assertEquals(1, cache.incrementAndExpire("existing", 60));
        assertEquals(Long.MAX_VALUE, cache.incrementAndExpire("overflow", 60));
        assertEquals("1", cache.get("existing"));
        assertTrue(cache.counterCapacityExceeded());
        assertEquals(2, cache.incrementAndExpire("existing", 60));
        cache.del("existing");
        assertTrue(cache.counterCapacityExceeded(), "Successful login/reset cannot clear the global failure window");
        now.set(61000);
        cache.removeExpiredEntries();
        assertFalse(cache.counterCapacityExceeded());
        assertEquals(1, cache.incrementAndExpire("new", 60));
    }

    @Test
    void concurrentAdmissionCannotExceedCapacity() throws Exception {
        var cache = new InMemoryCacheBackend(() -> 1000L, 50, 1_000_000);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int thread = 0; thread < 8; thread++) {
                int worker = thread;
                tasks.add(executor.submit(() -> { for (int i = 0; i < 500; i++) cache.setex(worker + ":" + i, 0, "value"); }));
            }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        assertEquals(50, storedEntries(cache, "strings").size());
    }
}
