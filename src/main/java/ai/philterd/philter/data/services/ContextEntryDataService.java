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

import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.utils.EnvUtils;

import java.util.List;
import java.util.Map;

/**
 * The token-to-replacement entries of each context, which give the same value the same replacement across
 * documents.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A token has at most one entry per user and context, even when the same token is redacted
 *   concurrently: every caller then gets the same replacement.</li>
 *   <li>Read counts are incremented atomically, so concurrent reads are not lost.</li>
 *   <li>A context never holds more than its maximum number of entries; the least read are evicted first.</li>
 *   <li>Listings return their total and page by offset without repeating or skipping entries.</li>
 * </ul>
 */
public interface ContextEntryDataService {

    int MAX_LIMIT = 100;

    int MAX_CONTEXT_SIZE =
            EnvUtils.getInt("MAX_CONTEXT_SIZE", ContextEntity.MAX_CONTEXT_SIZE);

    void incrementReads(final String id);

    List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, int limit);

    List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, int offset, int limit);

    /** A page of the context's entries in {@code sort} order. */
    List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, int offset, int limit,
            final Listings.Sort sort);

    int countByUserIdAndContext(final String userId, final String contextName);

    /**
     * Returns every entry in the context for the given user, without the paging cap applied by
     * {@link #findAllByUserIdAndContext}. Intended for exporting an entire mapping table. A context
     * is bounded by {@link #MAX_CONTEXT_SIZE}, so the result set is bounded too.
     */
    List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName);

    /** The outcome of importing a single mapping into a context. */
    enum ImportOutcome { INSERTED, OVERWRITTEN, SKIPPED }

    /**
     * Imports a single token-to-replacement mapping keyed by an already-computed token hash (the
     * original token is never available on import). If the token hash already exists in the context,
     * it is left untouched when {@code overwrite} is {@code false} (returning {@link
     * ImportOutcome#SKIPPED}) or updated when {@code overwrite} is {@code true} (returning {@link
     * ImportOutcome#OVERWRITTEN}). A new mapping is inserted otherwise, honoring {@link
     * #MAX_CONTEXT_SIZE} eviction, and returns {@link ImportOutcome#INSERTED}.
     */
    ImportOutcome importEntryByHash(final String userId, final String contextName, final String tokenHash,
            final String replacement, final String filterType, final boolean replacementUuid,
            final boolean overwrite);

    long deleteByIdAndUserId(final String id, final String userId);

    long deleteByIdAndUserIdAndContext(final String id, final String userId, final String contextName);

    boolean containsToken(final String userId, final String contextName, final String token);

    boolean containsReplacement(final String userId, final String contextName, final String replacement);

    String getReplacement(final String userId, final String contextName, final String token);

    ContextEntryEntity findOneEntryByToken(final String userId, final String contextName, final String token);

    void putReplacement(final String userId, final String contextName, final String token, final String replacement, final String filterType);

    /**
     * Records the replacement for a token unless one is already stored, and returns whichever is
     * stored afterwards. Two documents redacted at once must agree on a pseudonym, so the caller has
     * to use the returned value rather than the one it proposed: reading, deciding and inserting
     * separately let both callers decide "absent" and both insert.
     */
    ContextEntryEntity putReplacementIfAbsent(final String userId, final String contextName,
            final String token, final String replacement,
            final String filterType);

    void deleteByContextName(final String contextName, final String userId);

    /**
     * Counts a context's entries by filter type in one aggregation. Every entry is counted, so the
     * counts sum to the context's size; entries stored without a filter type are counted under the
     * {@code null} key.
     */
    Map<String, Long> getFilterTypeCounts(final String contextName, final String userId);

}
