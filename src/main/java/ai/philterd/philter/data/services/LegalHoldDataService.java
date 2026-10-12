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

import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.model.ServiceResponse;

import java.util.List;

/**
 * Manages legal holds: named, audited blocks on deletion or purge of governance evidence.
 *
 * <p>A hold has a {@code reference} (caller-supplied, unique per owner), a {@code scopeType}
 * ({@code document_chain} or {@code user}), and a {@code scopeValue}. For a {@code document_chain} hold the
 * scope value is the document ID. A {@code user} hold covers all of its owner's evidence, matched on the
 * owner alone; its scope value is the owner's username, kept for display and never used for matching.
 * Multiple holds may protect the same evidence simultaneously; releasing one hold never
 * unblocks evidence still covered by another.
 *
 * <p>Hold changes and ledger deletions share an owner-scoped guard.
 * Hold checks alone are read-only observations and do not serialize a later deletion.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A reference is unique per owner: setting a second hold with the same reference is refused and writes
 *   nothing.</li>
 *   <li>Holds never expire on their own; they end only when released.</li>
 *   <li>Hold changes and ledger deletions for one owner are serialized across every instance (see
 *   {@link LedgerDataService}).</li>
 *   <li>Listings return their total, page by offset without repeating or skipping holds, sort by the fields
 *   the API offers, and search references as case-insensitive plain text (see {@link Listings}).</li>
 * </ul>
 */
public interface LegalHoldDataService {

    /** Reasons a hold change is refused with 409, carried in the response's details. */
    String REASON_HOLD_EXISTS = "hold_exists";

    String REASON_OPERATION_IN_PROGRESS = "operation_in_progress";

    /**
     * Creates a new legal hold. The reference must be unique for the given owner.
     *
     * @return 201 on success; 409 if a hold with that reference already exists for this user;
     *         400 if required fields are missing or the scope type is invalid.
     */
    ServiceResponse create(final String requestId, final String reference,
            final String scopeType, final String scopeValue,
            final String reason, final String userId,
            final String setByUserId);

    /**
     * Releases (removes) the hold with the given reference for the given owner.
     *
     * @return 200 on success; 404 if no such hold exists for this owner.
     */
    ServiceResponse release(final String requestId, final String reference,
            final String userId);

    /** Hold mutations must participate in owner coordination and auditing. */
    String save(final LegalHoldEntity entity);

    void update(final LegalHoldEntity entity);

    /**
     * Returns the hold with the given reference for the given owner, or {@code null} if none exists.
     */
    LegalHoldEntity findByReference(final String reference, final String userId);

    /**
     * A page of the user's holds, or every user's when {@code userId} is null, whose references contain
     * {@code q}, and their total.
     */
    Listings.Page<LegalHoldEntity> list(final String userId, final String q, final Listings.Sort sort,
            final int offset, final int limit);

    /**
     * Returns a page of all holds owned by the given user, most recently set first.
     */
    List<LegalHoldEntity> findAllByUserId(final String userId, final int offset, final int limit);

    /**
     * Returns a page of all holds across all users, most recently set first. Admin use only.
     */
    List<LegalHoldEntity> findAll(final int offset, final int limit);

    /** Returns the total number of holds owned by the given user. */
    int countByUserId(final String userId);

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
     * <p>This is the primary gate for {@code deleteByDocumentId}, so it is a single lookup.
     */
    boolean isProtectedDocument(final String userId, final String documentId);

    /**
     * Returns {@code true} if any active hold exists for the given user (regardless of scope type).
     *
     * <p>This is the gate for age-based purges ({@code deleteChainsByUserIdAndOlderThan}) and
     * bulk user-evidence deletes. If any hold is active, the purge is blocked in its entirety
     * because the age-based query does not know which documents are covered by document_chain holds.
     */
    boolean hasAnyHold(final String userId);

    /**
     * Returns all holds that currently block deletion of the given document. Used to build the
     * error message shown to the operator when a delete is refused.
     */
    List<LegalHoldEntity> findBlockingHoldsForDocument(final String userId,
            final String documentId);

    /**
     * Returns all holds that currently block an age-based purge for the given user. Used to build
     * the error message shown to the operator when a purge is refused.
     */
    List<LegalHoldEntity> findAllHoldsForUser(final String userId);

}
