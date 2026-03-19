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

import ai.philterd.philter.services.encryption.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * An implementation of {@link Cache} for managing context-based token replacements.
 */
public class ContextCache extends Cache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextCache.class);

    /**
     * TTL for context cache entries in seconds (60 minutes).
     */
    private static final int CONTEXT_CACHE_TTL_SECONDS = 3600;

    /**
     * Creates a new context cache.
     *
     * @param host     The hostname of the Valkey server.
     * @param port     The port of the Valkey server.
     * @param password The password for the Valkey server.
     * @param ssl      Whether to use SSL for the connection.
     */
    public ContextCache(final String host, final int port, final String password, final boolean ssl) {
        super(host, port, password, ssl);
    }

    /**
     * Sets a token replacement in the cache.
     *
     * @param context     The context.
     * @param token       The token.
     * @param replacement The replacement value.
     */
    public void setTokenReplacement(final String context, final String token, final String replacement) {

        // The token must be hashed.
        final String tokenHash = EncryptionService.hashSha256(token);

        try (final Jedis jedis = pool.getResource()) {
            jedis.hset(context, tokenHash, replacement);
            // Set TTL of 60 minutes on the cache entry
            jedis.expire(context, CONTEXT_CACHE_TTL_SECONDS);
        }

    }

    /**
     * Gets a replacement for a token from the cache.
     *
     * @param context The context.
     * @param token   The token.
     * @return The replacement value, or <code>null</code> if not found.
     */
    public String getReplacement(final String context, final String token) {

        // The replacement is not encrypted and does not need to be decrypted.

        // The token must be hashed.
        final String tokenHash = EncryptionService.hashSha256(token);

        try (final Jedis jedis = pool.getResource()) {
            return jedis.hget(context, tokenHash);
        }

    }

    /**
     * Checks if the cache contains a replacement for a token.
     *
     * @param context The context.
     * @param token   The token.
     * @return <code>true</code> if the cache contains a replacement; otherwise <code>false</code>.
     */
    public boolean containsToken(final String context, final String token) {

        // The token needs to be encrypted.

        final String tokenHash = EncryptionService.hashSha256(token);

        try (final Jedis jedis = pool.getResource()) {
            return jedis.hexists(context, tokenHash);
        }

    }

    /**
     * Deletes a context from the cache.
     *
     * @param context The context to delete.
     */
    public void deleteContext(final String context) {

        LOGGER.info("Deleting context {} from cache.", context);

        try (final Jedis jedis = pool.getResource()) {
            jedis.del(context);
        }

    }

}