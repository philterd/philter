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

import ai.philterd.philter.utils.PathSafeNames;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.vectors.MongoVectorService;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteConcernException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.List;

public class ContextDataService extends AbstractService<ContextEntity> {

    public static final int MAXIMUM_CONTEXTS_PER_USER = 10;

    /** Reasons a create is refused with 409, carried in the response's details. */
    public static final String REASON_CONTEXT_EXISTS = "context_exists";
    public static final String REASON_CONTEXT_LIMIT_REACHED = "context_limit_reached";
    public static final int MAX_LIMIT = 100;

    private final ContextEntryDataService contextEntryService;
    private final ContextCache contextCache;
    private final MongoClient mongoClient;

    public ContextDataService(final MongoClient mongoClient, final ContextCache contextCache, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "contexts", auditEventPublisher);
        this.contextEntryService = new ContextEntryDataService(mongoClient, auditEventPublisher, contextCache);
        this.contextCache = contextCache;
        this.mongoClient = mongoClient;

        // Context names are unique within an owner. Incompatible schemas fail startup.
        ensureIndex(Indexes.ascending("user_id", "context_name"),
                new IndexOptions().unique(true).name(NAME_INDEX));

        // Each context holds one of its user's numbered slots, so the limit is enforced by this index
        // rather than by a count that a concurrent create can slip past. Contexts written before slots
        // existed get one first, or the index could not be built.
        assignMissingSlots();
        ensureIndex(Indexes.ascending("user_id", "slot"), new IndexOptions().unique(true).name(SLOT_INDEX));
    }

    private static final String NAME_INDEX = "user_id_context_name_unique";
    private static final String SLOT_INDEX = "user_id_slot_unique";

    /**
     * Gives every context without a slot the lowest slots its user has free, in {@code _id} order. The
     * assignment depends only on the stored contexts and each write is conditional on the slot still
     * being missing, so instances starting together assign the same slots. A user who already has more
     * contexts than the limit gets slots past it; {@link #create} refuses that user until they are under
     * the limit again.
     */
    private void assignMissingSlots() {
        // Filters.eq(field, null) matches a missing field and a null one alike.
        for (final ObjectId userId : collection.distinct("user_id", Filters.eq("slot", null), ObjectId.class)) {
            final java.util.Set<Integer> used = usedSlots(userId);
            int next = 0;
            for (final Document document : collection.find(Filters.and(Filters.eq("user_id", userId),
                    Filters.eq("slot", null))).sort(Sorts.ascending("_id"))) {
                while (used.contains(next)) {
                    next++;
                }
                collection.updateOne(Filters.and(Filters.eq("_id", document.getObjectId("_id")), Filters.eq("slot", null)),
                        Updates.set("slot", next));
                used.add(next);
            }
        }
    }

    private java.util.Set<Integer> usedSlots(final ObjectId userId) {
        final java.util.Set<Integer> used = new java.util.HashSet<>();
        for (final Document document : collection.find(Filters.and(Filters.eq("user_id", userId), Filters.ne("slot", null)))
                .projection(Projections.include("slot"))) {
            used.add(document.getInteger("slot"));
        }
        return used;
    }

    public ServiceResponse create(final String contextName, final ObjectId userId) {
        return create(contextName, userId, false, false);
    }

    public ServiceResponse create(final String contextName, final ObjectId userId, final boolean disambiguation, final boolean ledger) {

        if(contextName == null || contextName.isBlank()) {
            return new ServiceResponse("Context name cannot be blank.", false, 400);
        }

        if (!PathSafeNames.isPathSafe(contextName)) {
            return new ServiceResponse("The context name " + PathSafeNames.RULE + ".", false, 400);
        }

        // Context names are unique per user, so reject a name the caller already uses. Another user may
        // hold the same name without conflict. Checked before the limit, so a duplicate is always reported
        // as one.
        if(findOne(contextName, userId) != null) {
            return new ServiceResponse("Context already exists.", false, 409, REASON_CONTEXT_EXISTS);
        }

        final ServiceResponse limitReached = new ServiceResponse("Maximum number of contexts reached.", false, 409,
                REASON_CONTEXT_LIMIT_REACHED);
        if(findAll(userId).size() >= MAXIMUM_CONTEXTS_PER_USER) {
            return limitReached;
        }

        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.setUserId(userId);
        contextEntity.setContextName(contextName);
        contextEntity.setDisambiguation(disambiguation);
        contextEntity.setLedger(ledger);

        // Claim the lowest free slot. The checks above are not atomic with the insert, so concurrent
        // creates can race for a slot or a name; the unique indexes settle both, and the loser of a slot
        // tries the next one. Each lost race means another create filled a slot, so this ends within the
        // limit; the cap only guards against contexts being created and deleted in tight alternation.
        for (int attempt = 0; attempt <= 2 * MAXIMUM_CONTEXTS_PER_USER; attempt++) {

            final java.util.Set<Integer> used = usedSlots(userId);
            Integer slot = null;
            for (int candidate = 0; candidate < MAXIMUM_CONTEXTS_PER_USER; candidate++) {
                if (!used.contains(candidate)) {
                    slot = candidate;
                    break;
                }
            }
            if (slot == null) {
                return limitReached;
            }
            contextEntity.setSlot(slot);

            try {
                final ObjectId objectId = save(contextEntity);
                return new ServiceResponse("Context created", true, objectId, 201);
            } catch (final MongoWriteException | MongoWriteConcernException ex) {
                if (!isDuplicateKey(ex)) {
                    throw ex;
                }
                // Another create took this slot first: try the next. Any other duplicate is the name,
                // which a create racing this one claimed: the same 409 the check above returns.
                if (!ex.getMessage().contains(SLOT_INDEX)) {
                    return new ServiceResponse("Context already exists.", false, 409, REASON_CONTEXT_EXISTS);
                }
            }

        }

        // Refused rather than over the limit; a retry succeeds once the contention stops.
        return limitReached;

    }

    /**
     * Returns true if the given write failure is a duplicate-key error (MongoDB error code 11000),
     * i.e. the insert lost the race against the unique index on {@code context_name}.
     */
    private static boolean isDuplicateKey(final RuntimeException ex) {
        if(ex instanceof MongoWriteException mongoWriteException) {
            return mongoWriteException.getError().getCategory() == ErrorCategory.DUPLICATE_KEY;
        }
        if(ex instanceof MongoWriteConcernException mongoWriteConcernException) {
            return mongoWriteConcernException.getCode() == 11000;
        }
        return false;
    }

    /**
     * Returns every context for every user. Intended for admin-only views; ordinary access must use the
     * owner-scoped {@link #findAll(ObjectId)}.
     */
    public List<ContextEntity> findAllAcrossUsers() {

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for (final Document document : collection.find()) {
            contextEntities.add(ContextEntity.fromDocument(document));
        }

        return contextEntities;

    }

    /**
     * Returns one page of contexts across every user, ordered by name. Intended for admin-only views;
     * ordinary access must use the owner-scoped {@link #findAll(ObjectId)}.
     */
    public List<ContextEntity> findAllAcrossUsers(final int offset, final int limit) {

        final List<ContextEntity> contextEntities = new ArrayList<>();

        for (final Document document : collection.find().sort(Sorts.ascending("context_name", "_id")).skip(offset).limit(limit)) {
            contextEntities.add(ContextEntity.fromDocument(document));
        }

        return contextEntities;

    }

    /** Returns the total number of contexts across every user (for admin paging). */
    public int countAllAcrossUsers() {
        return (int) collection.countDocuments();
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

    /**
     * Changes a context's settings. A {@code null} setting keeps its current value, so turning one on never
     * turns the other off. Audited as {@code context_updated}, naming each setting whose value changed, the
     * acting user, and the acting API key.
     */
    public ServiceResponse updateSettings(final String requestId, final String contextName, final ObjectId userId,
                                          final Boolean disambiguation, final Boolean ledger,
                                          final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        final ContextEntity existing = findOne(contextName, userId);
        if (existing == null) {
            return new ServiceResponse("Context does not exist.", false, 404);
        }

        final List<Bson> sets = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        if (disambiguation != null) {
            sets.add(Updates.set("disambiguation", disambiguation));
            if (disambiguation != existing.isDisambiguation()) {
                changed.add("entity_type_disambiguation: " + disambiguation);
            }
        }
        if (ledger != null) {
            sets.add(Updates.set("ledger", ledger));
            if (ledger != existing.isLedger()) {
                changed.add("ledger: " + ledger);
            }
        }
        if (sets.isEmpty()) {
            return new ServiceResponse("No setting was given.", false, 400);
        }

        collection.updateOne(Filters.and(Filters.eq("context_name", contextName), Filters.eq("user_id", userId)),
                Updates.combine(sets));

        if (!changed.isEmpty()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_UPDATED, actingUserId, existing.getId(), null,
                    "context: " + contextName + ", " + String.join(", ", changed) + ", api_key: " + actingApiKeyId);
        }

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

        // Eviction happens in deleteByContextName, beside the delete it belongs to, so a failure in
        // between cannot leave the entries gone and the cache still answering.

        return new ServiceResponse("Context emptied successfully.", true);

    }

    public ServiceResponse deleteByName(final String contextName, final ObjectId userId) {
        // A bare delete is treated as a non-admin request: it can only delete the caller's own context.
        return deleteByName(contextName, userId, false);
    }

    public ServiceResponse deleteByName(final String contextName, final ObjectId requesterUserId, final boolean requesterIsAdmin) {

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

        // Eviction happens in deleteByContextName, beside the delete it belongs to.

        return new ServiceResponse("Context deleted successfully.", true);

    }

}
