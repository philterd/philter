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
package ai.philterd.philter.services.context;

import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The context service a redaction uses for one user's context: token replacements from the context's stored
 * entries, read through the request's cache, and context values, such as the random date shift, stored with the
 * context itself.
 */
public class StoredContextService implements ContextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoredContextService.class);

    private final ContextCache contextCache;
    private final ContextEntryDataService contextEntryService;
    private final ContextDataService contextDataService;
    private final String userId;
    private final String contextName;

    // The context's values this request has read or stored, so the database is read once per key per request
    // rather than once per date.
    private final Map<String, String> values = new ConcurrentHashMap<>();

    public StoredContextService(final ContextCache contextCache, final ContextEntryDataService contextEntryService,
                                final ContextDataService contextDataService, final String userId, final String contextName) {

        // A context name is the user's own label and can describe whose data it holds, so it is not logged.
        LOGGER.info("Instantiating StoredContextService for user {}", userId);

        this.contextCache = contextCache;
        this.contextEntryService = contextEntryService;
        this.contextDataService = contextDataService;
        this.userId = userId;
        this.contextName = contextName;

    }

    @Override
    public boolean containsToken(final String token) {

        if (contextCache.containsToken(userId, contextName, contextEntryService.tokenHash(userId, token))) {
            return true;
        }

        return contextEntryService.containsToken(userId, contextName, token);

    }

    @Override
    public boolean containsReplacement(final String replacement) {
        return contextEntryService.containsReplacement(userId, contextName, replacement);
    }

    @Override
    public String getReplacement(final String token) {

        final String tokenHash = contextEntryService.tokenHash(userId, token);
        final ContextCache.CachedReplacement cached = contextCache.getReplacement(userId, contextName, tokenHash);
        if (cached != null) {
            contextEntryService.incrementReads(cached.entryId());
            return cached.replacement();
        }

        final long observedAt = System.currentTimeMillis();
        final ContextEntryEntity entry = contextEntryService.findOneEntryByToken(userId, contextName, token);
        if (entry == null) {
            return null;
        }

        contextEntryService.incrementReads(entry.getId());
        contextCache.setTokenReplacement(userId, contextName, tokenHash, entry.getId(), entry.getReplacement(), observedAt);

        return entry.getReplacement();

    }

    @Override
    public void putReplacement(final String token, final String replacement, final String filterType) {
        storeReplacement(token, replacement, filterType);

    }

    @Override
    public String computeReplacementIfAbsent(final String token, final String filterType, final Supplier<String> replacementSupplier) {

        final String existing = getReplacement(token);
        if (existing != null) {
            return existing;
        }

        // The stored value, not the proposed one. Two documents redacted at once both find nothing
        // and both propose; only one is kept, and both must use it or they disagree on the pseudonym.
        final String stored = storeReplacement(token, replacementSupplier.get(), filterType);

        return stored != null ? stored : getReplacement(token);

    }

    /**
     * Keeps the value with the context rather than among its entries, so it is never evicted, deleted with an
     * entry, or listed, and is kept when the entries are cleared.
     */
    @Override
    public String computeValueIfAbsent(final String key, final String filterType, final Supplier<String> valueSupplier) {

        // Within this request the supplier runs at most once per key. Across requests, each proposes and the one
        // stored first is used by all.
        return values.computeIfAbsent(ContextDataService.valueKey(key), storedKey -> {

            final ContextEntity context = contextDataService.findOneByNameAndUserId(contextName, userId);
            final String stored = context == null ? null : context.getValues().get(storedKey);
            if (stored != null) {
                return stored;
            }

            final String proposed = valueSupplier.get();
            final String kept = contextDataService.putValueIfAbsent(userId, contextName, storedKey, proposed);

            // Null when the context was deleted during the request: nothing is stored, but this request keeps using it.
            return kept == null ? proposed : kept;

        });

    }

    /** Stores the replacement unless one exists, caches whichever is stored, and returns it. */
    private String storeReplacement(final String token, final String replacement, final String filterType) {

        final long observedAt = System.currentTimeMillis();
        final ContextEntryEntity entry =
                contextEntryService.putReplacementIfAbsent(userId, contextName, token, replacement, filterType);

        if (entry == null) {
            return null;
        }

        contextCache.setTokenReplacement(userId, contextName, contextEntryService.tokenHash(userId, token), entry.getId(),
                entry.getReplacement(), observedAt);

        return entry.getReplacement();

    }

}
