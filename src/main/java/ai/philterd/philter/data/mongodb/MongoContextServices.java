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
package ai.philterd.philter.data.mongodb;

import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.context.ContextServices;
import ai.philterd.philter.services.context.StoredContextService;
import com.mongodb.client.MongoClient;

/** Context services backed by MongoDB's context entries. */
public class MongoContextServices implements ContextServices {

    private final MongoClient mongoClient;
    private final AuditEventPublisher auditEventPublisher;

    public MongoContextServices(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        this.mongoClient = mongoClient;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Override
    public ContextService forContext(final ContextCache contextCache, final String userId, final String contextName) {
        return new StoredContextService(contextCache,
                new MongoContextEntryDataService(mongoClient, auditEventPublisher, contextCache), userId, contextName);
    }

}
