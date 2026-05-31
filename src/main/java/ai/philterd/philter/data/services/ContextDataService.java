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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.vectors.MongoVectorService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

public class ContextDataService extends AbstractService<ContextEntity> {

    public static final int MAXIMUM_CONTEXTS_PER_USER = 10;
    public static final int MAX_LIMIT = 100;

    private final ContextEntryDataService contextEntryService;
    private final ContextCache contextCache;
    private final MongoClient mongoClient;

    public ContextDataService(final MongoClient mongoClient, final ContextCache contextCache, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "contexts", auditEventPublisher);
        this.contextEntryService = new ContextEntryDataService(mongoClient, auditEventPublisher);
        this.contextCache = contextCache;
        this.mongoClient = mongoClient;

        // Contexts are listed by user and looked up by (user_id, context_name).
        ensureIndex(Indexes.ascending("user_id", "context_name"));
    }

    public ServiceResponse create(final String contextName, final ObjectId userId) {
        return create(contextName, userId, false, false);
    }

    public ServiceResponse create(final String contextName, final ObjectId userId, final boolean disambiguation, final boolean ledger) {

        if(contextName == null || contextName.isBlank()) {
            return new ServiceResponse("Context name cannot be blank.", false, 400);
        }

        if(findAll(userId).size() >= MAXIMUM_CONTEXTS_PER_USER) {
            return new ServiceResponse("Maximum number of contexts reached.", false, 412);
        }

        if(findOne(contextName, userId) != null) {
            return new ServiceResponse("Context already exists.", false, 409);
        }

        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.setUserId(userId);
        contextEntity.setContextName(contextName);
        contextEntity.setDisambiguation(disambiguation);
        contextEntity.setLedger(ledger);
        final ObjectId objectId = save(contextEntity);

        return new ServiceResponse("Context created", true, objectId, 201);

    }

    public List<ContextEntity> findAll(final ObjectId userId) {

        final Document query = new Document();

        if (userId != null) {
            query.append("user_id", userId);
        }

        final Iterable<Document> documents = collection.find(query);

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {

            final ContextEntity contextEntity = ContextEntity.fromDocument(document);
            contextEntities.add(contextEntity);

        }

        return contextEntities;

    }

    public ContextEntity findOneByIdAndUserId(final ObjectId id, final ObjectId userId) {

        final Document query = new Document("_id", id).append("user_id", userId);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ContextEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public ContextEntity findOneByNameAndUserId(final String contextName, final ObjectId userId) {

        final Document query = new Document("context_name", contextName).append("user_id", userId);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ContextEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public List<ContextEntity> findAll(final ObjectId userId, final int offset, final int limit) {
        return findAll(userId, offset, limit, null, null);
    }

    public List<ContextEntity> findAll(final ObjectId userId, final int offset, final int limit, final String sortField, final String sortDirection) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        // Apply sorting if specified
        final Document sortDocument = new Document();
        if (sortField != null && !sortField.isBlank()) {
            final int direction = "DESC".equalsIgnoreCase(sortDirection) ? -1 : 1;
            sortDocument.append(sortField, direction);
        }

        final Document query = new Document();

        if (userId != null) {
            query.append("user_id", userId);
        }

        final Iterable<Document> documents;
        if (sortDocument.isEmpty()) {
            documents = collection.find(query).skip(offset).limit(effectiveLimit);
        } else {
            documents = collection.find(query).sort(sortDocument).skip(offset).limit(effectiveLimit);
        }

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {

            final ContextEntity contextEntity = ContextEntity.fromDocument(document);
            contextEntities.add(contextEntity);

        }

        return contextEntities;

    }

    public int count(final ObjectId userId) {

        final Document query = new Document();

        if (userId != null) {
            query.append("user_id", userId);
        }

        return (int) collection.countDocuments(query);

    }

    public ContextEntity findOne(final String contextName, final ObjectId userId) {

        final Document query = new Document("context_name", contextName).append("user_id", userId);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ContextEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public ServiceResponse updateSettings(final String contextName, final ObjectId userId, final boolean disambiguation, final boolean ledger) {

        final ContextEntity existing = findOne(contextName, userId);
        if (existing == null) {
            return new ServiceResponse("Context does not exist.", false, 404);
        }

        final Bson filter = Filters.and(Filters.eq("context_name", contextName), Filters.eq("user_id", userId));
        final Bson update = Updates.combine(Updates.set("disambiguation", disambiguation), Updates.set("ledger", ledger));
        collection.updateOne(filter, update);

        return new ServiceResponse("Context updated.", true);

    }

    public ServiceResponse emptyByName(final String contextName, final ObjectId userId) {

        // Make sure the context exists.
        final ContextEntity contextEntity = findOne(contextName, userId);

        if(contextEntity == null) {
            return new ServiceResponse("Context does not exist.", false, 400);
        }

        // Delete the individual context entries for this context.
        contextEntryService.deleteByContextName(contextName, userId);

        // Delete the span-disambiguation vectors learned for this context so emptying it also
        // clears the training data, leaving no orphaned vectors in MongoDB.
        new MongoVectorService(mongoClient, userId, auditEventPublisher).deleteByContext(contextName);

        // Remove this context from the cache.
        contextCache.deleteContext(contextName);

        return new ServiceResponse("Context emptied successfully.", true);

    }

    public ServiceResponse deleteByName(final String contextName, final ObjectId userId) {
        // A bare delete is treated as a non-admin request: it can only delete the caller's own context.
        return deleteByName(contextName, userId, false);
    }

    public ServiceResponse deleteByName(final String contextName, final ObjectId requesterUserId, final boolean requesterIsAdmin) {

        // Safeguard to prevent deleting the default context.
        if("default".equalsIgnoreCase(contextName)) {
            return new ServiceResponse("The default context cannot be deleted.", false);
        }

        // Make sure the context exists. The lookup is scoped to the requester, so a non-admin can
        // only ever find (and therefore delete) a context they created.
        final ContextEntity contextEntity = findOne(contextName, requesterUserId);

        if(contextEntity == null) {
            return new ServiceResponse("Context does not exist.", false, 400);
        }

        // A context may be deleted only by the user that created it or by an admin. This is the
        // single authorization gate for deletion; it guards the invariant even if the lookup above
        // is ever changed to be owner-agnostic.
        final boolean isCreator = contextEntity.getUserId().equals(requesterUserId);
        if(!isCreator && !requesterIsAdmin) {
            return new ServiceResponse("You are not authorized to delete this context.", false, 403);
        }

        // Scope all deletions to the context's owner. The lookup above is scoped to the requester,
        // so in the current call paths the owner is the requester; resolving the owner from the
        // entity keeps the deletions correct if the lookup is ever made owner-agnostic.
        final ObjectId ownerUserId = contextEntity.getUserId();

        final Document filter = new Document("context_name", contextName).append("user_id", ownerUserId);
        collection.deleteOne(filter);

        // Delete the individual context entries for this context.
        contextEntryService.deleteByContextName(contextName, ownerUserId);

        // Delete the span-disambiguation vectors learned for this context so they do not linger
        // in MongoDB after the context is gone.
        new MongoVectorService(mongoClient, ownerUserId, auditEventPublisher).deleteByContext(contextName);

        // Remove this context from the cache.
        contextCache.deleteContext(contextName);

        return new ServiceResponse("Context deleted successfully.", true);

    }

}
