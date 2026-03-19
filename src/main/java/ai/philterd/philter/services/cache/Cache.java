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
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * An abstract class for a cache.
 */
public abstract class Cache {

    private static final Logger LOGGER = LoggerFactory.getLogger(Cache.class);

    protected final JedisPool pool;

    /**
     * Creates a new cache.
     * @param host The hostname of the Valkey server.
     * @param port The port of the Valkey server.
     * @param password The password for the Valkey server.
     * @param ssl Whether to use SSL for the connection.
     */
    public Cache(final String host, final int port, final String password, final boolean ssl) {

        final JedisPoolConfig poolConfig = new JedisPoolConfig();

        // Keep this conservative in Lambda; tune based on in-process concurrency
        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(0);

        // Avoid hanging forever
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWaitMillis(2_000);

        // Helps avoid stale connections in reused execution contexts
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        // Optional: periodic idle connection validation/eviction
        poolConfig.setTimeBetweenEvictionRunsMillis(30_000);
        poolConfig.setMinEvictableIdleTimeMillis(60_000);

        final int timeout = 5000;

        LOGGER.info("Connecting to cache at {}:{} with timeout of {}ms", host, port, timeout);

        if(password != null && !password.isEmpty()) {
            this.pool = new JedisPool(poolConfig, host, port, timeout, password, ssl);
        } else {
            this.pool = new JedisPool(poolConfig, host, port, timeout, ssl);
        }

    }

    /**
     * Closes the cache connection pool.
     */
    public void close() {
        pool.close();
    }

}
