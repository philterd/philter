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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.utils.PathSafeNames;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** MongoDB implementation of {@link LegalHoldDataService}. */
public class MongoLegalHoldDataService extends AbstractService<LegalHoldEntity> implements LegalHoldDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegalHoldDataService.class);
    private final EvidenceOperationGuard guard;
    private final MongoCollection<Document> holds;

    public MongoLegalHoldDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "legal_holds", auditEventPublisher);

        guard = new EvidenceOperationGuard(mongoClient);
        holds = collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);

        // Fast lookup by owner + reference (uniqueness constraint).
        holds.createIndex(Indexes.ascending("user_id", "reference"), new IndexOptions().unique(true));
        // Hold-check queries: is any hold active for this user?
        ensureIndex(Indexes.ascending("user_id", "scope_type", "scope_value"));
    }

    @Override
    public ServiceResponse create(final String requestId, final String reference,
                                   final String scopeType, final String scopeValue,
                                   final String reason, final String userId,
                                   final String setByUserId) {

        if (reference == null || reference.isBlank()) {
            return new ServiceResponse("Reference is required.", false, 400);
        }
        // The reference is how the hold is released, through a path; one that cannot be put in a path
        // would make a hold that keeps blocking deletion and cannot be released.
        if (!PathSafeNames.isPathSafe(reference)) {
            return new ServiceResponse("The reference " + PathSafeNames.RULE + ".", false, 400);
        }
        if (!LegalHoldEntity.SCOPE_DOCUMENT_CHAIN.equals(scopeType)
                && !LegalHoldEntity.SCOPE_USER.equals(scopeType)) {
            return new ServiceResponse("Invalid scope type. Must be 'document_chain' or 'user'.", false, 400);
        }
        if (scopeValue == null || scopeValue.isBlank()) {
            return new ServiceResponse("Scope value is required.", false, 400);
        }

        return guard.execute(userId, "create_hold", () -> {
            if (findByReference(reference, userId) != null) {
                return new ServiceResponse("A hold with reference '" + reference + "' already exists.", false, 409, REASON_HOLD_EXISTS);
            }

            final LegalHoldEntity hold = new LegalHoldEntity();
            hold.setUserId(userId);
            hold.setReference(reference);
            hold.setScopeType(scopeType);
            hold.setScopeValue(scopeValue);
            hold.setReason(reason);
            hold.setSetAt(new Date());
            hold.setSetByUserId(setByUserId);

            holds.insertOne(LegalHoldDocuments.toDocument(hold));

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_SET, userId, null,
                    null, "reference: " + reference + ", scopeType: " + scopeType
                            + ", scopeValue: " + scopeValue);

            return new ServiceResponse("Legal hold '" + reference + "' set.", true, 201);
        });
    }

    @Override
    public ServiceResponse release(final String requestId, final String reference,
                                    final String userId) {

        return guard.execute(userId, "release_hold", () -> {
            final LegalHoldEntity hold = findByReference(reference, userId);
            if (hold == null) {
                return new ServiceResponse("Hold '" + reference + "' not found.", false, 404);
            }

            holds.deleteOne(Filters.and(
                    Filters.eq("user_id", MongoIds.objectId(userId)),
                    Filters.eq("reference", reference)));

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_RELEASED, userId, null,
                    null, "reference: " + reference);

            return new ServiceResponse("Legal hold '" + reference + "' released.", true, 200);
        });
    }

    @Override
    public String save(final LegalHoldEntity entity) {
        throw new UnsupportedOperationException("Use create to set a legal hold.");
    }

    @Override
    public void update(final LegalHoldEntity entity) {
        throw new UnsupportedOperationException("Use create/release to change legal holds.");
    }

    @Override
    public LegalHoldEntity findByReference(final String reference, final String userId) {
        final Document doc = holds.find(Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("reference", reference))).first();
        return doc != null ? LegalHoldDocuments.fromDocument(doc) : null;
    }

    @Override
    public Listings.Page<LegalHoldEntity> list(final String userId, final String q, final Listings.Sort sort,
                                               final int offset, final int limit) {
        return MongoListings.page(holds, MongoListings.all(userId == null ? null : Filters.eq("user_id", MongoIds.objectId(userId)),
                MongoListings.contains("reference", q)), sort, offset, limit, LegalHoldDocuments::fromDocument);
    }

    @Override
    public List<LegalHoldEntity> findAllByUserId(final String userId, final int offset, final int limit) {
        final FindIterable<Document> docs = holds.find(Filters.eq("user_id", MongoIds.objectId(userId)))
                .sort(Sorts.descending("set_at"))
                .skip(offset)
                .limit(limit);
        return toList(docs);
    }

    @Override
    public List<LegalHoldEntity> findAll(final int offset, final int limit) {
        final FindIterable<Document> docs = holds.find()
                .sort(Sorts.descending("set_at", "_id"))
                .skip(offset)
                .limit(limit);
        return toList(docs);
    }

    @Override
    public int countByUserId(final String userId) {
        return (int) holds.countDocuments(Filters.eq("user_id", MongoIds.objectId(userId)));
    }

    @Override
    public boolean isProtectedDocument(final String userId, final String documentId) {
        final Bson query = Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.or(
                        Filters.and(
                                Filters.eq("scope_type", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN),
                                Filters.eq("scope_value", documentId)),
                        Filters.eq("scope_type", LegalHoldEntity.SCOPE_USER)));
        return holds.find(query).first() != null;
    }

    @Override
    public boolean hasAnyHold(final String userId) {
        return holds.find(Filters.eq("user_id", MongoIds.objectId(userId))).first() != null;
    }

    @Override
    public List<LegalHoldEntity> findBlockingHoldsForDocument(final String userId,
                                                               final String documentId) {
        final Bson query = Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.or(
                        Filters.and(
                                Filters.eq("scope_type", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN),
                                Filters.eq("scope_value", documentId)),
                        Filters.eq("scope_type", LegalHoldEntity.SCOPE_USER)));
        return toList(holds.find(query));
    }

    @Override
    public List<LegalHoldEntity> findAllHoldsForUser(final String userId) {
        return toList(holds.find(Filters.eq("user_id", MongoIds.objectId(userId))));
    }

    private static List<LegalHoldEntity> toList(final Iterable<Document> docs) {
        final List<LegalHoldEntity> result = new ArrayList<>();
        for (final Document doc : docs) {
            result.add(LegalHoldDocuments.fromDocument(doc));
        }
        return result;
    }
    @Override
    protected Document toDocument(final LegalHoldEntity entity) {
        return LegalHoldDocuments.toDocument(entity);
    }

}
