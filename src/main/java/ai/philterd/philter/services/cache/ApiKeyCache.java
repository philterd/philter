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

import ai.philterd.philter.data.entities.ApiKeyEntity;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * An implementation of {@link Cache} for managing context-based token replacements.
 */
public class ApiKeyCache extends Cache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyCache.class);

    private final Gson gson;

    /**
     * Creates a new API key cache.
     * @param host The hostname of the Redis server.
     * @param port The port of the Redis server.
     * @param password The password for the Redis server.
     * @param ssl Whether to use SSL for the connection.
     */
    public ApiKeyCache(final String host, final int port, final String password, final boolean ssl) {
        super(host, port, password, ssl);
        this.gson = new Gson();
    }

    public void delete(final String apiKey) {

        try(final Jedis jedis = pool.getResource()) {
            jedis.del(buildKey(apiKey));
        }

    }

    public void insert(final String apiKey, final ApiKeyEntity apiKeyEntity) {

        final String json = gson.toJson(apiKeyEntity);

        try(final Jedis jedis = pool.getResource()) {

            // Insert with a TTL of 10 minutes.
            jedis.setex(buildKey(apiKey), 300, json);

        }

    }

    public ApiKeyEntity get(final String apiKey) {

        try(final Jedis jedis = pool.getResource()) {

            final String json = jedis.get(buildKey(apiKey));
            return gson.fromJson(json, ApiKeyEntity.class);

        }

    }

    public boolean containsApiKey(final String apiKey) {

        try(final Jedis jedis = pool.getResource()) {
            final boolean contains = jedis.exists(buildKey(apiKey));
            if(contains) {
               LOGGER.info("API key found in cache.");
            }
            return contains;
        }

    }

    private String buildKey(final String userId) {
        return "apikey_" + userId;
    }

}