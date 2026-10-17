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

import ai.philterd.philter.audit.AuditDetails;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.vectors.VectorServices;
import ai.philterd.philter.utils.PathSafeNames;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for contexts, the same in every storage implementation: what a valid context is, the per-user limit
 * held by numbered slots, who may delete a context, what changing its settings audits, and that emptying or
 * deleting a context also removes its entries and learned vectors. An implementation stores the contexts.
 */
public abstract class StoredContextDataService implements ContextDataService {

    protected final AuditEventPublisher auditEventPublisher;
    private final ContextEntryDataService contextEntryService;
    private final VectorServices vectorServices;

    protected StoredContextDataService(final AuditEventPublisher auditEventPublisher,
                                       final ContextEntryDataService contextEntryService,
                                       final VectorServices vectorServices) {
        this.auditEventPublisher = auditEventPublisher;
        this.contextEntryService = contextEntryService;
        this.vectorServices = vectorServices;
    }

    /**
     * Stores a new context and returns its id.
     *
     * @throws DuplicateRecordException when the owner already has a context with its name or its slot.
     */
    protected abstract String insert(final ContextEntity context);

    /** The slots the user's contexts hold. */
    protected abstract Set<Integer> usedSlots(final String userId);

    /** Sets the given settings, those that are not null, on the owner's context. */
    protected abstract void updateSettings(final String userId, final String contextName, final Boolean disambiguation,
            final String disambiguationScope, final Boolean ledger);

    /** Removes the owner's context, not its entries or vectors. */
    protected abstract void delete(final String userId, final String contextName);

    @Override
    public ServiceResponse create(final String contextName, final String userId) {
        return create(contextName, userId, false, false);
    }

    @Override
    public ServiceResponse create(final String contextName, final String userId, final boolean disambiguation, final boolean ledger) {
        return create(contextName, userId, disambiguation, ContextEntity.DISAMBIGUATION_SCOPE_DOCUMENT, ledger);
    }

    @Override
    public ServiceResponse create(final String contextName, final String userId, final boolean disambiguation,
                                  final String disambiguationScope, final boolean ledger) {

        if(contextName == null || contextName.isBlank()) {
            return new ServiceResponse("Context name cannot be blank.", false, 400);
        }

        if (!PathSafeNames.isPathSafe(contextName)) {
            return new ServiceResponse("The context name " + PathSafeNames.RULE + ".", false, 400);
        }

        final String scope = ContextEntity.disambiguationScopeOf(disambiguationScope);
        if (scope == null) {
            return new ServiceResponse(DISAMBIGUATION_SCOPE_REFUSAL, false, 400);
        }

        // Context names are unique per user, so reject a name the caller already uses. Another user may
        // hold the same name without conflict. Checked before the limit, so a duplicate is always reported
        // as one.
        final ServiceResponse exists = new ServiceResponse("Context already exists.", false, 409, REASON_CONTEXT_EXISTS);
        if(findOne(contextName, userId) != null) {
            return exists;
        }

        final ServiceResponse limitReached = new ServiceResponse("Maximum number of contexts reached.", false, 409,
                REASON_CONTEXT_LIMIT_REACHED);
        if(count(userId) >= MAXIMUM_CONTEXTS_PER_USER) {
            return limitReached;
        }

        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.setUserId(userId);
        contextEntity.setContextName(contextName);
        contextEntity.setDisambiguation(disambiguation);
        contextEntity.setDisambiguationScope(scope);
        contextEntity.setLedger(ledger);
        contextEntity.setTimestamp(new Date());

        // Claim the lowest free slot. The checks above are not atomic with the insert, so concurrent
        // creates can race for a slot or a name; the uniqueness constraints settle both, and the loser of a
        // slot tries the next one. Each lost race means another create filled a slot, so this ends within
        // the limit; the cap only guards against contexts being created and deleted in tight alternation.
        for (int attempt = 0; attempt <= 2 * MAXIMUM_CONTEXTS_PER_USER; attempt++) {

            final Set<Integer> used = usedSlots(userId);
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
                final String id = insert(contextEntity);
                return new ServiceResponse("Context created", true, id, 201);
            } catch (final DuplicateRecordException ex) {
                // A create racing this one claimed the name: the same 409 the check above returns.
                // Otherwise it took this slot first: try the next.
                if (findOne(contextName, userId) != null) {
                    return exists;
                }
            }

        }

        // Refused rather than over the limit; a retry succeeds once the contention stops.
        return limitReached;

    }

    @Override
    public ContextEntity findOne(final String contextName, final String userId) {
        return findOneByNameAndUserId(contextName, userId);
    }

    @Override
    public ServiceResponse updateSettings(final String requestId, final String contextName, final String userId,
                                          final Boolean disambiguation, final String disambiguationScope,
                                          final Boolean ledger, final String actingUserId,
                                          final String actingApiKeyId) {

        final String scope = disambiguationScope == null ? null : ContextEntity.disambiguationScopeOf(disambiguationScope);
        if (disambiguationScope != null && scope == null) {
            return new ServiceResponse(DISAMBIGUATION_SCOPE_REFUSAL, false, 400);
        }

        final ContextEntity existing = findOne(contextName, userId);
        if (existing == null) {
            return new ServiceResponse("Context does not exist.", false, 404);
        }

        if (disambiguation == null && scope == null && ledger == null) {
            return new ServiceResponse("No setting was given.", false, 400);
        }

        final List<String> changed = new ArrayList<>();
        if (disambiguation != null && disambiguation != existing.isDisambiguation()) {
            changed.add("entity_type_disambiguation: " + disambiguation);
        }
        if (scope != null && !scope.equals(existing.getDisambiguationScope())) {
            changed.add("disambiguation_scope: " + scope.toLowerCase(Locale.ROOT));
        }
        if (ledger != null && ledger != existing.isLedger()) {
            changed.add("ledger: " + ledger);
        }

        updateSettings(userId, contextName, disambiguation, scope, ledger);

        if (!changed.isEmpty()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_UPDATED, actingUserId, existing.getId(), null,
                    "context: " + AuditDetails.quote(contextName) + ", " + String.join(", ", changed) + ", api_key: " + actingApiKeyId);
        }

        return new ServiceResponse("Context updated.", true);

    }

    @Override
    public ServiceResponse emptyByName(final String contextName, final String userId) {

        if(findOne(contextName, userId) == null) {
            return new ServiceResponse("Context does not exist.", false, 404);
        }

        // Deleting the entries also evicts them from the context cache, beside the delete it belongs to,
        // so a failure in between cannot leave the entries gone and the cache still answering.
        contextEntryService.deleteByContextName(contextName, userId);

        // Emptying a context also clears the span-disambiguation vectors learned for it.
        vectorServices.deleteByContext(userId, contextName);

        return new ServiceResponse("Context emptied successfully.", true);

    }

    @Override
    public ServiceResponse deleteByName(final String contextName, final String userId) {
        // A bare delete is treated as a non-admin request: it can only delete the caller's own context.
        return deleteByName(contextName, userId, false);
    }

    @Override
    public ServiceResponse deleteByName(final String contextName, final String requesterUserId, final boolean requesterIsAdmin) {

        // The lookup is scoped to the requester, so a non-admin can only ever find (and therefore
        // delete) a context they created.
        final ContextEntity contextEntity = findOne(contextName, requesterUserId);

        if(contextEntity == null) {
            return new ServiceResponse("Context does not exist.", false, 404);
        }

        // A context may be deleted only by the user that created it or by an admin. This is the
        // single authorization gate for deletion; it guards the invariant even if the lookup above
        // is ever changed to be owner-agnostic.
        final boolean isCreator = contextEntity.getUserId().equals(requesterUserId);
        if(!isCreator && !requesterIsAdmin) {
            return new ServiceResponse("You are not authorized to delete this context.", false, 403);
        }

        // Scope all deletions to the context's owner, which keeps them correct if the lookup above is
        // ever made owner-agnostic.
        final String ownerUserId = contextEntity.getUserId();

        delete(ownerUserId, contextName);
        contextEntryService.deleteByContextName(contextName, ownerUserId);
        vectorServices.deleteByContext(ownerUserId, contextName);

        return new ServiceResponse("Context deleted successfully.", true);

    }

}
