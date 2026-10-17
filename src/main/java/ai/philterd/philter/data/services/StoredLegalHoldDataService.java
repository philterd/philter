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
import ai.philterd.philter.utils.PathSafeNames;

import java.util.Date;

/**
 * The rules for legal holds, the same in every storage implementation: what a valid hold is, that hold changes
 * take the owner's {@link EvidenceGuard}, and what is audited. An implementation stores and finds the holds.
 */
public abstract class StoredLegalHoldDataService implements LegalHoldDataService {

    protected final AuditEventPublisher auditEventPublisher;
    private final EvidenceGuard guard;

    protected StoredLegalHoldDataService(final AuditEventPublisher auditEventPublisher, final EvidenceGuard guard) {
        this.auditEventPublisher = auditEventPublisher;
        this.guard = guard;
    }

    /**
     * Stores a new hold.
     *
     * @throws DuplicateRecordException when the owner already has a hold with its reference.
     */
    protected abstract void insert(final LegalHoldEntity hold);

    /** Removes the owner's hold with this reference. */
    protected abstract void delete(final String reference, final String userId);

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
            final ServiceResponse exists =
                    new ServiceResponse("A hold with reference '" + reference + "' already exists.", false, 409, REASON_HOLD_EXISTS);
            if (findByReference(reference, userId) != null) {
                return exists;
            }

            final LegalHoldEntity hold = new LegalHoldEntity();
            hold.setUserId(userId);
            hold.setReference(reference);
            hold.setScopeType(scopeType);
            hold.setScopeValue(scopeValue);
            hold.setReason(reason);
            hold.setSetAt(new Date());
            hold.setSetByUserId(setByUserId);

            try {
                insert(hold);
            } catch (final DuplicateRecordException race) {
                return exists;
            }

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_SET, userId, null,
                    null, "reference: " + reference + ", scopeType: " + scopeType
                            + ", scopeValue: " + scopeValue);

            return new ServiceResponse("Legal hold '" + reference + "' set.", true, 201);
        });
    }

    @Override
    public ServiceResponse release(final String requestId, final String reference,
                                    final String userId, final String releasedByUserId,
                                    final boolean releasedByAdministrator) {

        return guard.execute(userId, "release_hold", () -> {
            final LegalHoldEntity hold = findByReference(reference, userId);
            if (hold == null) {
                return new ServiceResponse("Hold '" + reference + "' not found.", false, 404);
            }

            if (!releasedByAdministrator
                    && (hold.getSetByUserId() == null || !hold.getSetByUserId().equals(releasedByUserId))) {
                return new ServiceResponse("Only the user who set this hold or an administrator can release it.",
                        false, 403);
            }

            delete(reference, userId);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_RELEASED, userId, null,
                    null, "reference: " + reference + ", releasedBy: " + releasedByUserId);

            return new ServiceResponse("Legal hold '" + reference + "' released.", true, 200);
        });
    }

    @Override
    public boolean isProtectedDocument(final String userId, final String documentId) {
        return !findBlockingHoldsForDocument(userId, documentId).isEmpty();
    }

    @Override
    public boolean hasAnyHold(final String userId) {
        return !findAllHoldsForUser(userId).isEmpty();
    }

}
