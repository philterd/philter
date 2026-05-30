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

    private InMemoryCacheBackend() {
    }

    private static long expiryFrom(final int ttlSeconds) {
        return ttlSeconds > 0 ? System.currentTimeMillis() + (ttlSeconds * 1000L) : 0L;
    }

    @Override
    public void setex(final String key, final int ttlSeconds, final String value) {
        strings.put(key, new Entry<>(value, expiryFrom(ttlSeconds)));
    }

    @Override
    public String get(final String key) {
        final Entry<String> entry = strings.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            strings.remove(key);
            return null;
        }
        return entry.value();
    }

    @Override
    public boolean exists(final String key) {
        return get(key) != null;
    }

    @Override
    public void del(final String key) {
        strings.remove(key);
        hashes.remove(key);
    }

    @Override
    public void hset(final String key, final String field, final String value) {
        final Entry<Map<String, String>> entry = hashes.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired(System.currentTimeMillis())) {
                return new Entry<>(new ConcurrentHashMap<>(), 0L);
            }
            return existing;
        });
        entry.value().put(field, value);
    }

    @Override
    public String hget(final String key, final String field) {
        final Map<String, String> hash = liveHash(key);
        return hash == null ? null : hash.get(field);
    }

    @Override
    public boolean hexists(final String key, final String field) {
        final Map<String, String> hash = liveHash(key);
        return hash != null && hash.containsKey(field);
    }

    @Override
    public void expire(final String key, final int ttlSeconds) {
        hashes.computeIfPresent(key, (k, existing) ->
                existing.isExpired(System.currentTimeMillis())
                        ? null
                        : new Entry<>(existing.value(), expiryFrom(ttlSeconds)));
    }

    @Override
    public void close() {
        // Nothing to release for an in-memory store.
    }

    private Map<String, String> liveHash(final String key) {
        final Entry<Map<String, String>> entry = hashes.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            hashes.remove(key);
            return null;
        }
        return entry.value();
    }

}
