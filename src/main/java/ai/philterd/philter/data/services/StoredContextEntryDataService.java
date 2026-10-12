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
package ai.philterd.philter.data.services;

import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.encryption.ContextTokenHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules for context entries, the same in every storage implementation: tokens are stored only as their
 * hashes, a context holds at most {@link #MAX_CONTEXT_SIZE} entries with the least read evicted first, an import
 * skips or overwrites an existing token as asked, and the context cache forgets whatever the storage no longer
 * holds. An implementation stores the entries and makes a token's first replacement win.
 */
public abstract class StoredContextEntryDataService implements ContextEntryDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextEntryDataService.class);

    private static final Pattern UUID_REGEX_PATTERN = Pattern.compile(
            "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"
    );

    private final ContextCache contextCache;

    protected StoredContextEntryDataService(final ContextCache contextCache) {
        this.contextCache = contextCache;
    }

    /**
     * The same stored entries, evicting from {@code contextCache} instead: a redaction uses the cache it opened
     * for the request.
     */
    public abstract StoredContextEntryDataService withCache(final ContextCache contextCache);

    /**
     * Stores the entry unless its context already has one for its token hash, and returns whichever is stored
     * afterwards. Of concurrent calls for one token, exactly one stores its entry and all return that entry.
     */
    protected abstract ContextEntryEntity insertIfAbsent(final ContextEntryEntity entry);

    /** The context's entry for this token hash, or null. */
    protected abstract ContextEntryEntity findByTokenHash(final String userId, final String contextName, final String tokenHash);

    /** Stores a new entry. */
    protected abstract void insert(final ContextEntryEntity entry);

    /** Changes the replacement of the context's entry for this token hash. */
    protected abstract void updateReplacement(final String userId, final String contextName, final String tokenHash,
            final String replacement, final String filterType, final boolean replacementUuid);

    /** Removes and returns the user's entry with this id, in this context when it is not null, or returns null. */
    protected abstract ContextEntryEntity deleteById(final String id, final String userId, final String contextName);

    /** The context's least-read entry, the oldest among equals, or null when it is empty. */
    protected abstract ContextEntryEntity findLeastRead(final String userId, final String contextName);

    /** Removes every entry in the context. */
    protected abstract void deleteContext(final String userId, final String contextName);

    @Override
    public List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, final int limit) {
        return findAllByUserIdAndContext(userId, contextName, 0, limit);
    }

    @Override
    public List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, final int offset, final int limit) {
        return findAllByUserIdAndContext(userId, contextName, offset, limit, new Listings.Sort("timestamp", true));
    }

    @Override
    public ImportOutcome importEntryByHash(final String userId, final String contextName, final String tokenHash,
                                           final String replacement, final String filterType, final boolean replacementUuid,
                                           final boolean overwrite) {

        if (findByTokenHash(userId, contextName, tokenHash) != null) {

            if (!overwrite) {
                return ImportOutcome.SKIPPED;
            }

            updateReplacement(userId, contextName, tokenHash, replacement, filterType, replacementUuid);

            // The stored replacement changed, so anything holding the old one has to forget it.
            evict(userId, contextName, tokenHash);

            return ImportOutcome.OVERWRITTEN;

        }

        evictIfFull(userId, contextName);
        insert(entry(userId, contextName, tokenHash, replacement, filterType, replacementUuid));

        return ImportOutcome.INSERTED;

    }

    @Override
    public long deleteByIdAndUserId(final String id, final String userId) {
        return delete(id, userId, null);
    }

    @Override
    public long deleteByIdAndUserIdAndContext(final String id, final String userId, final String contextName) {
        return delete(id, userId, contextName);
    }

    private long delete(final String id, final String userId, final String contextName) {
        final ContextEntryEntity deleted = deleteById(id, userId, contextName);
        if (deleted == null) {
            return 0;
        }
        evict(userId, deleted.getContextName(), deleted.getTokenHash());
        return 1;
    }

    @Override
    public boolean containsToken(final String userId, final String contextName, final String token) {
        return findOneEntryByToken(userId, contextName, token) != null;
    }

    @Override
    public String getReplacement(final String userId, final String contextName, final String token) {
        final ContextEntryEntity entry = findOneEntryByToken(userId, contextName, token);
        if (entry == null) {
            return null;
        }
        incrementReads(entry.getId());
        return entry.getReplacement();
    }

    @Override
    public ContextEntryEntity findOneEntryByToken(final String userId, final String contextName, final String token) {
        return findByTokenHash(userId, contextName, ContextTokenHasher.hash(token));
    }

    @Override
    public void putReplacement(final String userId, final String contextName, final String token, final String replacement, final String filterType) {
        putReplacementIfAbsent(userId, contextName, token, replacement, filterType);
    }

    @Override
    public ContextEntryEntity putReplacementIfAbsent(final String userId, final String contextName,
                                                     final String token, final String replacement,
                                                     final String filterType) {

        final ContextEntryEntity stored = insertIfAbsent(entry(userId, contextName, ContextTokenHasher.hash(token),
                replacement, filterType, UUID_REGEX_PATTERN.matcher(replacement).matches()));

        if (stored != null && replacement.equals(stored.getReplacement())) {
            // This call is the one that created the entry, so it is the one that may have filled the
            // context. Trimmed afterwards rather than before: a put that changes nothing must not
            // evict anything.
            evictIfFull(userId, contextName);
        }

        return stored;

    }

    @Override
    public void deleteByContextName(final String contextName, final String userId) {

        deleteContext(userId, contextName);

        // Here rather than only in the caller, so nothing between the delete and the caller's own
        // eviction can leave the entries gone and the cache still answering.
        if (contextCache != null) {
            contextCache.deleteContext(userId, contextName);
        }

    }

    private static ContextEntryEntity entry(final String userId, final String contextName, final String tokenHash,
                                            final String replacement, final String filterType,
                                            final boolean replacementUuid) {
        final ContextEntryEntity entry = new ContextEntryEntity();
        entry.setUserId(userId);
        entry.setContextName(contextName);
        entry.setTokenHash(tokenHash);
        entry.setReplacement(replacement);
        entry.setReads(0L);
        entry.setTimestamp(new Date());
        entry.setFilterType(filterType);
        entry.setReplacementUuid(replacementUuid);
        return entry;
    }

    /** Forgets a mapping the store no longer has, or no longer has under that value. */
    private void evict(final String userId, final String contextName, final String tokenHash) {
        if (contextCache != null && contextName != null && tokenHash != null) {
            contextCache.evictTokenHash(userId, contextName, tokenHash);
        }
    }

    private void evictIfFull(final String userId, final String contextName) {

        if (countByUserIdAndContext(userId, contextName) < MAX_CONTEXT_SIZE) {
            return;
        }

        final ContextEntryEntity victim = findLeastRead(userId, contextName);

        if (victim != null && deleteById(victim.getId(), userId, contextName) != null) {
            evict(userId, contextName, victim.getTokenHash());
            LOGGER.info("Evicted context entry {} from context {} (reads={}) to honor MAX_CONTEXT_SIZE={}",
                    victim.getId(), contextName, victim.getReads(), MAX_CONTEXT_SIZE);
        }

    }

}
