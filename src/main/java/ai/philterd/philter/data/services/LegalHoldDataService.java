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
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Manages legal holds: named, audited blocks on deletion or purge of governance evidence.
 *
 * <p>A hold has a {@code reference} (caller-supplied, unique per owner), a {@code scopeType}
 * ({@code document_chain} or {@code user}), and a {@code scopeValue} (document ID or user ID).
 * Multiple holds may protect the same evidence simultaneously; releasing one hold never
 * unblocks evidence still covered by another.
 *
 * <p>Hold enforcement: call {@link #isProtectedDocument} or {@link #hasAnyHold} before any
 * delete or purge operation on ledger entries. Both methods return quickly via an indexed
 * {@code find().first()} query so the overhead on the hot path is minimal.
 */
public class LegalHoldDataService extends AbstractService<LegalHoldEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegalHoldDataService.class);

    public LegalHoldDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "legal_holds", auditEventPublisher);

        // Fast lookup by owner + reference (uniqueness constraint).
        ensureIndex(Indexes.ascending("user_id", "reference"), new IndexOptions().unique(true));
        // Hold-check queries: is any hold active for this user?
        ensureIndex(Indexes.ascending("user_id", "scope_type", "scope_value"));
    }

    /**
     * Creates a new legal hold. The reference must be unique for the given owner.
     *
     * @return 201 on success; 409 if a hold with that reference already exists for this user;
     *         400 if required fields are missing or the scope type is invalid.
     */
    public ServiceResponse create(final String requestId, final String reference,
                                   final String scopeType, final String scopeValue,
                                   final String reason, final ObjectId userId,
                                   final ObjectId setByUserId) {

        if (reference == null || reference.isBlank()) {
            return new ServiceResponse("Reference is required.", false, 400);
        }
        if (!LegalHoldEntity.SCOPE_DOCUMENT_CHAIN.equals(scopeType)
                && !LegalHoldEntity.SCOPE_USER.equals(scopeType)) {
            return new ServiceResponse("Invalid scope type. Must be 'document_chain' or 'user'.", false, 400);
        }
        if (scopeValue == null || scopeValue.isBlank()) {
            return new ServiceResponse("Scope value is required.", false, 400);
        }

        if (findByReference(reference, userId) != null) {
            return new ServiceResponse("A hold with reference '" + reference + "' already exists.", false, 409);
        }

        final LegalHoldEntity hold = new LegalHoldEntity();
        hold.setUserId(userId);
        hold.setReference(reference);
        hold.setScopeType(scopeType);
        hold.setScopeValue(scopeValue);
        hold.setReason(reason);
        hold.setSetAt(new Date());
        hold.setSetByUserId(setByUserId);

        save(hold);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_SET, userId, null,
                null, "reference: " + reference + ", scopeType: " + scopeType
                        + ", scopeValue: " + scopeValue);

        return new ServiceResponse("Legal hold '" + reference + "' set.", true, 201);
    }

    /**
     * Releases (removes) the hold with the given reference for the given owner.
     *
     * @return 200 on success; 404 if no such hold exists for this owner.
     */
    public ServiceResponse release(final String requestId, final String reference,
                                    final ObjectId userId) {

        final LegalHoldEntity hold = findByReference(reference, userId);
        if (hold == null) {
            return new ServiceResponse("Hold '" + reference + "' not found.", false, 404);
        }

        collection.deleteOne(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("reference", reference)));

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_RELEASED, userId, null,
                null, "reference: " + reference);

        return new ServiceResponse("Legal hold '" + reference + "' released.", true, 200);
    }

    /**
     * Returns the hold with the given reference for the given owner, or {@code null} if none exists.
     */
    public LegalHoldEntity findByReference(final String reference, final ObjectId userId) {
        final Document doc = collection.find(Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("reference", reference))).first();
        return doc != null ? LegalHoldEntity.fromDocument(doc) : null;
    }

    /**
     * Returns a page of all holds owned by the given user, most recently set first.
     */
    public List<LegalHoldEntity> findAllByUserId(final ObjectId userId, final int offset, final int limit) {
        final FindIterable<Document> docs = collection.find(Filters.eq("user_id", userId))
                .sort(Sorts.descending("set_at"))
                .skip(offset)
                .limit(limit);
        return toList(docs);
    }

    /**
     * Returns a page of all holds across all users, most recently set first. Admin use only.
     */
    public List<LegalHoldEntity> findAll(final int offset, final int limit) {
        final FindIterable<Document> docs = collection.find()
                .sort(Sorts.descending("set_at"))
                .skip(offset)
                .limit(limit);
        return toList(docs);
    }

    /** Returns the total number of holds owned by the given user. */
    public int countByUserId(final ObjectId userId) {
        return (int) collection.countDocuments(Filters.eq("user_id", userId));
    }

    /** Returns the total number of holds across all users. Admin use only. */
    public int countAll() {
        return (int) collection.countDocuments();
    }

    /**
     * Returns {@code true} if any active hold protects ledger entries for the given document.
     *
     * <p>A document is protected when either:
     * <ul>
     *   <li>a {@code document_chain} hold exists for this owner with {@code scopeValue} equal to
     *       the document ID, or</li>
     *   <li>a {@code user} hold exists for this owner (covering all of the user's evidence).</li>
     * </ul>
     *
     * <p>This is the primary gate for {@code deleteByDocumentId}. It executes a single indexed
     * {@code find().first()} and returns immediately.
     */
    public boolean isProtectedDocument(final ObjectId userId, final String documentId) {
        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.or(
                        Filters.and(
                                Filters.eq("scope_type", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN),
                                Filters.eq("scope_value", documentId)),
                        Filters.eq("scope_type", LegalHoldEntity.SCOPE_USER)));
        return collection.find(query).first() != null;
    }

    /**
     * Returns {@code true} if any active hold exists for the given user (regardless of scope type).
     *
     * <p>This is the gate for age-based purges ({@code deleteChainsByUserIdAndOlderThan}) and
     * bulk user-evidence deletes. If any hold is active, the purge is blocked in its entirety
     * because the age-based query does not know which documents are covered by document_chain holds.
     */
    public boolean hasAnyHold(final ObjectId userId) {
        return collection.find(Filters.eq("user_id", userId)).first() != null;
    }

    /**
     * Returns all holds that currently block deletion of the given document. Used to build the
     * error message shown to the operator when a delete is refused.
     */
    public List<LegalHoldEntity> findBlockingHoldsForDocument(final ObjectId userId,
                                                               final String documentId) {
        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.or(
                        Filters.and(
                                Filters.eq("scope_type", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN),
                                Filters.eq("scope_value", documentId)),
                        Filters.eq("scope_type", LegalHoldEntity.SCOPE_USER)));
        return toList(collection.find(query));
    }

    /**
     * Returns all holds that currently block an age-based purge for the given user. Used to build
     * the error message shown to the operator when a purge is refused.
     */
    public List<LegalHoldEntity> findAllHoldsForUser(final ObjectId userId) {
        return toList(collection.find(Filters.eq("user_id", userId)));
    }

    private static List<LegalHoldEntity> toList(final Iterable<Document> docs) {
        final List<LegalHoldEntity> result = new ArrayList<>();
        for (final Document doc : docs) {
            result.add(LegalHoldEntity.fromDocument(doc));
        }
        return result;
    }
}
