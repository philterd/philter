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
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ContextDataService extends AbstractService<ContextEntity> {

    public static final int MAXIMUM_CONTEXTS_PER_USER = 10;
    public static final int MAX_LIMIT = 100;

    private final ContextEntryDataService contextEntryService;
    private final ContextCache contextCache;

    public ContextDataService(final MongoClient mongoClient, final ContextCache contextCache, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "contexts", auditEventPublisher);
        this.contextEntryService = new ContextEntryDataService(mongoClient, auditEventPublisher);
        this.contextCache = contextCache;
    }

    public ServiceResponse create(final String contextName) {
        return create(contextName, false, false);
    }

    public ServiceResponse create(final String contextName, final boolean coref) {
        return create(contextName, coref, false);
    }

    public ServiceResponse create(final String contextName, final boolean coref, final boolean disambiguation) {

        if(contextName == null || contextName.isBlank()) {
            return new ServiceResponse("Context name cannot be blank.", false, 400);
        }

        if(findAll().size() >= MAXIMUM_CONTEXTS_PER_USER) {
            return new ServiceResponse("Maximum number of contexts reached.", false, 412);
        }

        if(findOne(contextName) != null) {
            return new ServiceResponse("Context already exists.", false, 409);
        }

        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.setContextName(contextName);
        contextEntity.setCoref(coref);
        contextEntity.setDisambiguation(disambiguation);
        final ObjectId objectId = save(contextEntity);

        return new ServiceResponse("Context created", true, objectId, 201);

    }

    public List<ContextEntity> findAll() {

        final Iterable<Document> documents = collection.find();

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {

            final ContextEntity contextEntity = ContextEntity.fromDocument(document);
            contextEntities.add(contextEntity);

        }

        return contextEntities;

    }

    public List<ContextEntity> findBySearchTerm(final String searchTerm, final int limit) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);
        final Pattern pattern = Pattern.compile(".*" + Pattern.quote(searchTerm) + ".*", Pattern.CASE_INSENSITIVE);

        final Bson query = Filters.and(
                Filters.regex("context_name", pattern)
        );

        final FindIterable<Document> documents = collection.find(query).limit(effectiveLimit);

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {
            final ContextEntity contextEntity = ContextEntity.fromDocument(document);
            contextEntities.add(contextEntity);
        }

        return contextEntities;

    }

    public List<ContextEntity> findAll(final int offset, final int limit) {
        return findAll(offset, limit, null, null);
    }

    public List<ContextEntity> findAll(final int offset, final int limit, final String sortField, final String sortDirection) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        // Apply sorting if specified
        final Document sortDocument = new Document();
        if (sortField != null && !sortField.isBlank()) {
            final int direction = "DESC".equalsIgnoreCase(sortDirection) ? -1 : 1;
            sortDocument.append(sortField, direction);
        }

        final Iterable<Document> documents;
        if (sortDocument.isEmpty()) {
            documents = collection.find().skip(offset).limit(effectiveLimit);
        } else {
            documents = collection.find().sort(sortDocument).skip(offset).limit(effectiveLimit);
        }

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {

            final ContextEntity contextEntity = ContextEntity.fromDocument(document);
            contextEntities.add(contextEntity);

        }

        return contextEntities;

    }

    public int count() {

        return (int) collection.countDocuments();

    }

    public ContextEntity findOneById(final ObjectId id, final ObjectId userId) {

        final Document query = new Document("_id", id).append("user_id", userId);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ContextEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public ContextEntity findOne(final String contextName) {

        final Document query = new Document("context_name", contextName);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ContextEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    public ServiceResponse emptyByName(final String contextName) {

        // Make sure the context exists.
        final ContextEntity contextEntity = findOne(contextName);

        if(contextEntity == null) {
            return new ServiceResponse("Context does not exist.", false, 400);
        }

        // Delete the individual context entries for this context.
        contextEntryService.deleteByContextName(contextName);

        // Remove this context from the cache.
        contextCache.deleteContext(contextName);

        return new ServiceResponse("Context emptied successfully.", true);

    }

    public ServiceResponse deleteByName(final String contextName) {

        // Safeguard to prevent deleting the default context.
        if(!"default".equalsIgnoreCase(contextName)) {

            // Make sure the context exists.
            final ContextEntity contextEntity = findOne(contextName);

            if(contextEntity == null) {
                return new ServiceResponse("Context does not exist.", false, 400);
            }

            final Document filter = new Document("context_name", contextName);
            collection.deleteOne(filter);

            // Delete the individual context entries for this context.
            contextEntryService.deleteByContextName(contextName);

            // Remove this context from the cache.
            contextCache.deleteContext(contextName);

            return new ServiceResponse("Context deleted successfully.", true);

        } else {
            return new ServiceResponse("The default context cannot be deleted.", false);
        }

    }

}
