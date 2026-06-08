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
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
     * Cached values are encoded as the entry's 24-character ObjectId hex followed by the replacement.
     * Storing the id lets the caller increment the entry's read count on a cache hit without a DB lookup.
     */
    private static final int ENTRY_ID_HEX_LENGTH = 24;

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
     * Builds the cache key for a context. Context names are unique per user, not globally, so the key
     * is namespaced by the owning user's id. Without this, two users with a same-named context (for
     * example the auto-created {@code default} context) would share a single cache entry.
     *
     * <p>A null {@code userId} is rejected: an unnamespaced key would collapse every user's same-named
     * context into one shared entry, leaking one user's token mappings to another.
     */
    private static String buildKey(final ObjectId userId, final String context) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null when building a context cache key.");
        }
        return userId.toHexString() + ":" + context;
    }

    /**
     * Sets a token replacement in the cache, tagged with the entry's id so the caller can later
     * increment the entry's read count on a cache hit.
     */
    public void setTokenReplacement(final ObjectId userId, final String context, final String token, final ObjectId entryId, final String replacement) {

        // The token must be hashed.
        final String tokenHash = EncryptionService.hashSha256(token);

        if (entryId == null) {
            // Without an id, the cache value would be ambiguous on read; skip caching rather than store a value
            // that can't be re-validated.
            return;
        }

        final String key = buildKey(userId, context);
        final String encoded = entryId.toHexString() + replacement;

        backend.hset(key, tokenHash, encoded);
        // Set TTL of 60 minutes on the cache entry.
        backend.expire(key, CONTEXT_CACHE_TTL_SECONDS);

    }

    /**
     * Gets a replacement for a token from the cache.
     *
     * @return The cached entry, or {@code null} if not present or the cached value is in an
     *         unrecognized (legacy) format.
     */
    public CachedReplacement getReplacement(final ObjectId userId, final String context, final String token) {

        final String tokenHash = EncryptionService.hashSha256(token);

        final String raw = backend.hget(buildKey(userId, context), tokenHash);

        if (raw == null || raw.length() < ENTRY_ID_HEX_LENGTH) {
            return null;
        }

        final String hexPrefix = raw.substring(0, ENTRY_ID_HEX_LENGTH);
        if (!ObjectId.isValid(hexPrefix)) {
            // Legacy value without an id prefix — treat as a cache miss so the caller refreshes from the DB.
            return null;
        }

        return new CachedReplacement(new ObjectId(hexPrefix), raw.substring(ENTRY_ID_HEX_LENGTH));

    }

    /**
     * A cached entry, paired with the id of the underlying database row.
     */
    public record CachedReplacement(ObjectId entryId, String replacement) {}

    /**
     * Checks if the cache contains a replacement for a token.
     *
     * @param userId  The id of the user that owns the context.
     * @param context The context.
     * @param token   The token.
     * @return <code>true</code> if the cache contains a replacement; otherwise <code>false</code>.
     */
    public boolean containsToken(final ObjectId userId, final String context, final String token) {

        // The token needs to be encrypted.

        final String tokenHash = EncryptionService.hashSha256(token);

        return backend.hexists(buildKey(userId, context), tokenHash);

    }

    /**
     * Deletes a context from the cache.
     *
     * @param userId  The id of the user that owns the context.
     * @param context The context to delete.
     */
    public void deleteContext(final ObjectId userId, final String context) {

        LOGGER.info("Deleting context {} for user {} from cache.", context, userId);

        backend.del(buildKey(userId, context));

    }

}