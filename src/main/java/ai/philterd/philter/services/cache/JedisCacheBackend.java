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
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * A {@link CacheBackend} backed by an external Valkey/Redis server via Jedis.
 */
public class JedisCacheBackend implements CacheBackend {

    private static final Logger LOGGER = LoggerFactory.getLogger(JedisCacheBackend.class);

    private final JedisPool pool;

    public JedisCacheBackend(final String host, final int port, final String password, final boolean ssl) {

        final JedisPoolConfig poolConfig = new JedisPoolConfig();

        poolConfig.setMaxTotal(16);
        poolConfig.setMaxIdle(16);
        poolConfig.setMinIdle(0);

        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWaitMillis(2_000);

        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        poolConfig.setTimeBetweenEvictionRunsMillis(30_000);
        poolConfig.setMinEvictableIdleTimeMillis(60_000);

        final int timeout = 5000;

        LOGGER.info("Connecting to cache at {}:{} with timeout of {}ms", host, port, timeout);

        if (password != null && !password.isEmpty()) {
            this.pool = new JedisPool(poolConfig, host, port, timeout, password, ssl);
        } else {
            this.pool = new JedisPool(poolConfig, host, port, timeout, ssl);
        }

    }

    @Override
    public void setex(final String key, final int ttlSeconds, final String value) {
        try (final Jedis jedis = pool.getResource()) {
            jedis.setex(key, ttlSeconds, value);
        }
    }

    @Override
    public String get(final String key) {
        try (final Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        }
    }

    @Override
    public boolean exists(final String key) {
        try (final Jedis jedis = pool.getResource()) {
            return jedis.exists(key);
        }
    }

    @Override
    public void del(final String key) {
        try (final Jedis jedis = pool.getResource()) {
            jedis.del(key);
        }
    }

    @Override
    public void hset(final String key, final String field, final String value) {
        try (final Jedis jedis = pool.getResource()) {
            jedis.hset(key, field, value);
        }
    }

    @Override
    public String hget(final String key, final String field) {
        try (final Jedis jedis = pool.getResource()) {
            return jedis.hget(key, field);
        }
    }

    @Override
    public boolean hexists(final String key, final String field) {
        try (final Jedis jedis = pool.getResource()) {
            return jedis.hexists(key, field);
        }
    }

    @Override
    public void expire(final String key, final int ttlSeconds) {
        try (final Jedis jedis = pool.getResource()) {
            jedis.expire(key, ttlSeconds);
        }
    }

    @Override
    public void close() {
        pool.close();
    }

}
