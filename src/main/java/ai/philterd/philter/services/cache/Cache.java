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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An abstract class for a cache.
 */
public abstract class Cache {

    private static final Logger LOGGER = LoggerFactory.getLogger(Cache.class);

    // Ensures the ephemeral-cache warning is printed only once per process.
    private static final AtomicBoolean EPHEMERAL_WARNING_PRINTED = new AtomicBoolean(false);

    protected final CacheBackend backend;

    /**
     * Creates a new cache. When no host is configured (null or blank) an in-memory, ephemeral cache
     * is used instead of connecting to Valkey/Redis.
     * @param host The hostname of the Valkey server, or null/blank to use an in-memory cache.
     * @param port The port of the Valkey server.
     * @param password The password for the Valkey server.
     * @param ssl Whether to use SSL for the connection.
     */
    public Cache(final String host, final int port, final String password, final boolean ssl) {

        if (host == null || host.isBlank()) {

            if (EPHEMERAL_WARNING_PRINTED.compareAndSet(false, true)) {
                System.out.println("WARNING: No cache host configured (CACHE_HOSTNAME). Using an in-memory cache. "
                        + "Cached data is ephemeral, is not shared across instances, and will be lost on restart. "
                        + "Configure a Valkey/Redis server for a durable, shared cache.");
            }

            this.backend = InMemoryCacheBackend.INSTANCE;

        } else {
            this.backend = new JedisCacheBackend(host, port, password, ssl);
        }

    }

    /**
     * Closes the cache, releasing any backing resources.
     */
    public void close() {
        backend.close();
    }

}
