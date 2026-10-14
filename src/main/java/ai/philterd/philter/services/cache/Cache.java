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

    // Ensures the unprotected-cache warning is printed only once per process.
    private static final AtomicBoolean UNPROTECTED_WARNING_PRINTED = new AtomicBoolean(false);

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
        this(host, port, password, ssl, InMemoryCacheBackend.INSTANCE);
    }

    /** As above, using {@code inMemory} rather than the shared in-memory store when no host is configured. */
    protected Cache(final String host, final int port, final String password, final boolean ssl,
                    final InMemoryCacheBackend inMemory) {

        if (host == null || host.isBlank()) {

            if (EPHEMERAL_WARNING_PRINTED.compareAndSet(false, true)) {
                System.out.println("WARNING: No cache host configured (CACHE_HOSTNAME). Using an in-process, "
                        + "in-memory cache. This is only safe for a SINGLE instance. Behind a load balancer or with "
                        + "multiple instances this is UNSAFE: the API-key and context caches are not shared, so a "
                        + "revoked API key stays usable on other instances until their cached copy expires. Set CACHE_HOSTNAME to a Valkey/Redis server for any multi-instance "
                        + "deployment.");
            }

            this.backend = inMemory;

        } else {
            warnIfUnprotected(host, password, ssl);
            // Encrypted and authenticated, so what is stored in Valkey can neither be read nor forged.
            this.backend = SealedCacheBackend.fromEnvironment(new JedisCacheBackend(host, port, password, ssl));
        }

    }

    /** Over the given backend, for tests. */
    protected Cache(final CacheBackend backend) {
        this.backend = backend;
    }

    /**
     * Warns, once per process, when Valkey is reached over a network without a password or without TLS.
     * Anyone who can reach an unprotected cache can delete or replace what Philter keeps there, so it should
     * be protected like the database.
     */
    static void warnIfUnprotected(final String host, final String password, final boolean ssl) {
        final String warning = unprotectedWarning(host, password, ssl);
        if (warning != null && UNPROTECTED_WARNING_PRINTED.compareAndSet(false, true)) {
            LOGGER.warn(warning);
        }
    }

    /** The warning for a cache reached this way, or null when there is nothing to warn about. */
    static String unprotectedWarning(final String host, final String password, final boolean ssl) {

        final boolean noPassword = password == null || password.isEmpty();
        if (isLoopback(host) || (!noPassword && ssl)) {
            return null;
        }

        return "The Valkey cache at " + host + " is used "
                + (noPassword && !ssl ? "without a password or TLS" : noPassword ? "without a password" : "without TLS")
                + ". Anyone who can reach it can delete or replace Philter's cached entries, so protect it like the "
                + "database: set CACHE_PASSWORD and CACHE_SSL, and allow only Philter's instances to connect.";

    }

    /** Whether the host is this machine, judged from its name alone without a DNS lookup. */
    static boolean isLoopback(final String host) {
        final String h = host.trim().toLowerCase(java.util.Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.matches("127(\\.\\d{1,3}){3}")
                || h.equals("::1") || h.equals("[::1]");
    }

    /**
     * Closes the cache, releasing any backing resources.
     */
    public void close() {
        backend.close();
    }

}
