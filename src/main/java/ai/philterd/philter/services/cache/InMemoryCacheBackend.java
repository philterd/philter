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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * An in-process, ephemeral {@link CacheBackend} used when no Valkey/Redis server is configured.
 *
 * <p>Contents live only in the JVM heap and are lost on restart, and are not shared across multiple
 * Philter instances. A single shared instance ({@link #INSTANCE}) is used so that callers which
 * construct a cache per request still share the same data within a process, mirroring how all callers
 * would otherwise share one external cache.
 */
public class InMemoryCacheBackend implements CacheBackend {

    /** Shared, process-wide instance. */
    public static final InMemoryCacheBackend INSTANCE = new InMemoryCacheBackend();

    /** A stored string value with an optional expiry (epoch millis; 0 means no expiry). */
    private record Entry<T>(T value, long expiresAtMillis) {
        boolean isExpired(final long nowMillis) {
            return expiresAtMillis > 0 && nowMillis >= expiresAtMillis;
        }
    }

    private final Map<String, Entry<String>> strings = new ConcurrentHashMap<>();
    private final Map<String, Entry<Map<String, String>>> hashes = new ConcurrentHashMap<>();

    private final LongSupplier clock;
    private final long maxBytes;
    private final int maxEntries;
    private long bytes;
    private int entries;
    private volatile long counterOverflowUntil;

    InMemoryCacheBackend(final LongSupplier clock, final int maxEntries, final long maxBytes) {
        if (maxEntries <= 0 || maxBytes <= 0) throw new IllegalArgumentException("Cache capacity must be positive.");
        this.clock = clock;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    // Conservative retained-size accounting: fixed object/map overhead plus UTF-16 character data.
    private static long weight(String... values) {
        long size = 256;
        for (String value : values) size += 64L + 2L * value.length();
        return size;
    }

    private boolean reserve(int count, long size) {
        if (entries + count > maxEntries || bytes + size > maxBytes) return false;
        entries += count;
        bytes += size;
        return true;
    }

    private synchronized void removeString(String key, Entry<String> observed) {
        if (strings.remove(key, observed)) { entries--; bytes -= weight(key, observed.value()); }
    }

    private synchronized void removeHash(String key, Entry<Map<String, String>> observed) {
        if (hashes.remove(key, observed)) {
            entries--;
            bytes -= weight(key);
            for (var field : observed.value().entrySet()) {
                entries--;
                bytes -= weight(field.getKey(), field.getValue());
            }
        }
    }

    @Override
    public boolean counterCapacityExceeded() {
        return clock.getAsLong() < counterOverflowUntil;
    }


    private InMemoryCacheBackend() {
        this(System::currentTimeMillis);
        // The singleton owns this daemon for the lifetime of the process. Cache wrappers are
        // short-lived borrowers, so closing one must not stop cleanup for all other callers.
        final var cleaner = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "philter-cache-expiry");
            thread.setDaemon(true);
            return thread;
        });
        cleaner.scheduleWithFixedDelay(this::removeExpiredEntries, 1, 1, TimeUnit.SECONDS);
    }

    // Isolated stores with a controllable clock for deterministic expiry/concurrency tests.
    InMemoryCacheBackend(final LongSupplier clock) {
        this(clock, ai.philterd.philter.utils.EnvUtils.getInt("IN_MEMORY_CACHE_MAX_ENTRIES", 100_000),
                ai.philterd.philter.utils.EnvUtils.getLong("IN_MEMORY_CACHE_MAX_BYTES", 64L * 1024 * 1024));
    }

    synchronized void removeExpiredEntries() {
        final long now = clock.getAsLong();
        strings.forEach((key, entry) -> {
            if (entry.isExpired(now)) {
                removeString(key, entry);
            }
        });
        hashes.forEach((key, entry) -> {
            if (entry.isExpired(now)) {
                removeHash(key, entry);
            }
        });
    }

    private long expiryFrom(final int ttlSeconds) {
        return ttlSeconds > 0 ? clock.getAsLong() + (ttlSeconds * 1000L) : 0L;
    }

    @Override
    public synchronized void setex(final String key, final int ttlSeconds, final String value) {
        final Entry<String> old = strings.get(key);
        if (old != null) removeString(key, old);
        if (reserve(1, weight(key, value))) strings.put(key, new Entry<>(value, expiryFrom(ttlSeconds)));
    }

    @Override
    public synchronized long incrementAndExpire(final String key, final int ttlSeconds) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("Counter TTL must be positive.");
        final long now = clock.getAsLong();
        Entry<String> old = strings.get(key);
        if (old != null && old.isExpired(now)) { removeString(key, old); old = null; }
        long count = 0;
        if (old != null) {
            try { count = Long.parseLong(old.value()); } catch (NumberFormatException ignored) { }
        }
        final long next = count == Long.MAX_VALUE ? count : count + 1;
        final String value = Long.toString(next);
        final long delta = weight(key, value) - (old == null ? 0 : weight(key, old.value()));
        if (!reserve(old == null ? 1 : 0, delta)) {
            // Never evict a live lockout counter. A single bounded overflow marker blocks logins
            // for the entire failure window, including usernames whose counters could not be stored.
            counterOverflowUntil = Math.max(counterOverflowUntil, expiryFrom(ttlSeconds));
            return Long.MAX_VALUE;
        }
        strings.put(key, new Entry<>(value, expiryFrom(ttlSeconds)));
        return next;
    }

    @Override
    public String get(final String key) {
        final Entry<String> entry = strings.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            removeString(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    public boolean exists(final String key) {
        return get(key) != null;
    }

    @Override
    public synchronized void del(final String key) {
        final var string = strings.get(key);
        if (string != null) removeString(key, string);
        final var hash = hashes.get(key);
        if (hash != null) removeHash(key, hash);
    }

    @Override
    public synchronized void hset(final String key, final String field, final String value) {
        var hash = hashes.get(key);
        if (hash != null && hash.isExpired(clock.getAsLong())) { removeHash(key, hash); hash = null; }
        final String old = hash == null ? null : hash.value().get(field);
        final int count = (hash == null ? 1 : 0) + (old == null ? 1 : 0);
        final long delta = (hash == null ? weight(key) : 0) + weight(field, value)
                - (old == null ? 0 : weight(field, old));
        if (!reserve(count, delta)) {
            // A failed cache refresh must not leave the previous field value looking current.
            hdel(key, field);
            return;
        }
        if (hash == null) {
            hash = new Entry<>(new ConcurrentHashMap<>(), 0L);
            hashes.put(key, hash);
        }
        hash.value().put(field, value);
    }

    @Override
    public String hget(final String key, final String field) {
        final Map<String, String> hash = liveHash(key);
        return hash == null ? null : hash.get(field);
    }

    @Override
    public synchronized void hdel(final String key, final String field) {
        final var hash = hashes.get(key);
        if (hash == null) return;
        if (hash.isExpired(clock.getAsLong())) { removeHash(key, hash); return; }
        final String old = hash.value().remove(field);
        if (old != null) { entries--; bytes -= weight(field, old); }
        if (hash.value().isEmpty()) removeHash(key, hash);
    }

    @Override
    public boolean hexists(final String key, final String field) {
        final Map<String, String> hash = liveHash(key);
        return hash != null && hash.containsKey(field);
    }

    @Override
    public synchronized void expire(final String key, final int ttlSeconds) {
        final var hash = hashes.get(key);
        if (hash == null) return;
        if (hash.isExpired(clock.getAsLong())) removeHash(key, hash);
        else hashes.put(key, new Entry<>(hash.value(), expiryFrom(ttlSeconds)));
    }

    @Override
    public void close() {
        // Shared process-owned store: a borrower must not stop its expiry daemon.
    }

    private Map<String, String> liveHash(final String key) {
        final Entry<Map<String, String>> entry = hashes.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            removeHash(key, entry);
            return null;
        }
        return entry.value();
    }

}
