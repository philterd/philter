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
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import com.mongodb.client.MongoClient;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

public class MongoContextService implements ContextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoContextService.class);

    private final ContextCache contextCache;
    private final ContextEntryDataService contextEntryService;
    private final ObjectId userId;
    private final String contextName;

    public MongoContextService(final MongoClient mongoClient, final ContextCache contextCache, final ObjectId userId, final String contextName, final AuditEventPublisher auditEventPublisher) {
        this(contextCache, new ContextEntryDataService(mongoClient, auditEventPublisher), userId, contextName);
    }

    MongoContextService(final ContextCache contextCache, final ContextEntryDataService contextEntryService, final ObjectId userId, final String contextName) {

        LOGGER.info("Instantiating MongoContextService for user {} and context {}", userId, contextName);

        this.contextCache = contextCache;
        this.contextEntryService = contextEntryService;
        this.userId = userId;
        this.contextName = contextName;

    }

    @Override
    public boolean containsToken(final String token) {

        if (contextCache.containsToken(contextName, token)) {
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

        final ContextCache.CachedReplacement cached = contextCache.getReplacement(contextName, token);
        if (cached != null) {
            contextEntryService.incrementReads(cached.entryId());
            return cached.replacement();
        }

        final ContextEntryEntity entry = contextEntryService.findOneEntryByToken(userId, contextName, token);
        if (entry == null) {
            return null;
        }

        contextEntryService.incrementReads(entry.getId());
        contextCache.setTokenReplacement(contextName, token, entry.getId(), entry.getReplacement());

        return entry.getReplacement();

    }

    @Override
    public void putReplacement(final String token, final String replacement, final String filterType) {

        contextEntryService.putReplacement(userId, contextName, token, replacement, filterType);

        final ContextEntryEntity entry = contextEntryService.findOneEntryByToken(userId, contextName, token);
        if (entry != null) {
            contextCache.setTokenReplacement(contextName, token, entry.getId(), entry.getReplacement());
        }

    }

    @Override
    public String computeReplacementIfAbsent(final String token, final String filterType, final Supplier<String> replacementSupplier) {
        final String existing = getReplacement(token);
        if (existing != null) {
            return existing;
        }
        final String replacement = replacementSupplier.get();
        putReplacement(token, replacement, filterType);
        return replacement;
    }

}
