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
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import com.mongodb.client.MongoClient;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MongoContextService implements ContextService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoContextService.class);

    private final ContextCache contextCache;
    private final ContextEntryDataService contextEntryService;
    private final ObjectId userId;
    private final String contextName;

    public MongoContextService(final MongoClient mongoClient, final ContextCache contextCache, final ObjectId userId, final String contextName, final AuditEventPublisher auditEventPublisher) {

        LOGGER.info("Instantiating MongoContextService for user {} and context {}", userId, contextName);

        this.contextCache = contextCache;
        this.contextEntryService = new ContextEntryDataService(mongoClient, auditEventPublisher);
        this.userId = userId;
        this.contextName = contextName;

    }

    @Override
    public boolean containsToken(final String token) {

        // Look in the cache first.
        if(contextCache.containsToken(contextName, token)) {

            return true;

        } else {

            // Look in the database.
            return contextEntryService.containsToken(userId, contextName, token);

        }

    }

    @Override
    public boolean containsReplacement(final String replacement) {

        // The replacement does not need to be encrypted.

        // There's no good way to find by value, so always look at the database.
        return contextEntryService.containsReplacement(userId, contextName, replacement);

    }

    @Override
    public String getReplacement(final String token) {

        // Try to get from the cache first.
        if(contextCache.containsToken(contextName, token)) {
            // TODO: When the replacement is retrieved from the cache, the ContextEntryEntity's read count does not get updated.
            // Need the ID of the ContextEntryEntity to update the read count.
            // With the ID, collection.UpdateOne(Filters.eq("_id", id), Updates.inc("reads",1));
            return contextCache.getReplacement(contextName, token);
        }

        // Not in the cache, so get from the database and insert into the cache.
        final String replacement = contextEntryService.getReplacement(userId, contextName, token);
        contextCache.setTokenReplacement(contextName, token, replacement);

        return replacement;

    }

    @Override
    public void putReplacement(final String token, final String replacement, final String filterType) {

        // Insert into the cache and the database.
        contextCache.setTokenReplacement(contextName, token, replacement);

        contextEntryService.putReplacement(userId, contextName, token, replacement, filterType);

    }

}