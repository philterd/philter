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

import ai.philterd.philter.services.encryption.ContextTokenHasher;
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
    private final java.util.function.LongSupplier clock;

    /**
     * Creates a new context cache.
     *
     * @param host     The hostname of the Valkey server.
     * @param port     The port of the Valkey server.
     * @param password The password for the Valkey server.
     * @param ssl      Whether to use SSL for the connection.
     */
    public ContextCache(final String host, final int port, final String password, final boolean ssl) {
        this(host, port, password, ssl, System::currentTimeMillis);
    }

    ContextCache(String host, int port, String password, boolean ssl, java.util.function.LongSupplier clock) {
        super(host, port, password, ssl);
        this.clock = clock;
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
        setTokenReplacement(userId, context, token, entryId, replacement, clock.getAsLong());
    }

    public void setTokenReplacement(final ObjectId userId, final String context, final String token,
                                    final ObjectId entryId, final String replacement, final long observedAt) {

        // The token must be hashed.
        final String tokenHash = ContextTokenHasher.hash(token);

        if (entryId == null) {
            // Without an id, the cache value would be ambiguous on read; skip caching rather than store a value
            // that can't be re-validated.
            return;
        }

        final String key = buildKey(userId, context);
        final String encoded = (observedAt + CONTEXT_CACHE_TTL_SECONDS * 1000L) + ":" + entryId.toHexString() + replacement;

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

        final String tokenHash = ContextTokenHasher.hash(token);

        final String value = backend.hget(buildKey(userId, context), tokenHash);
        if (value == null) return null;
        final int separator = value.indexOf(':');
        if (separator < 0) return null;
        try {
            if (clock.getAsLong() >= Long.parseLong(value.substring(0, separator))) return null;
        } catch (NumberFormatException invalid) { return null; }
        final String raw = value.substring(separator + 1);

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

        return getReplacement(userId, context, token) != null;

    }

    /**
     * Deletes a context from the cache.
     *
     * @param userId  The id of the user that owns the context.
     * @param context The context to delete.
     */
    /**
     * Forgets one mapping, by the hash the entry is stored under. Import and deletion work from the
     * hash: the original token is not available to them.
     */
    public void evictTokenHash(final ObjectId userId, final String context, final String tokenHash) {
        if (tokenHash == null) {
            return;
        }
        backend.hdel(buildKey(userId, context), tokenHash);
    }

    public void deleteContext(final ObjectId userId, final String context) {

        LOGGER.info("Deleting context {} for user {} from cache.", context, userId);

        backend.del(buildKey(userId, context));

    }

}