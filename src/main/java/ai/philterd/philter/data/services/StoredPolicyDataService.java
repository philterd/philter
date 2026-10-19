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
import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.filtering.PolicyLoadCheck;
import ai.philterd.philter.services.models.PhEyeModels;
import ai.philterd.philter.services.policies.ManagedPolicyLoader;
import ai.philterd.philter.services.policies.PolicyResolutionException;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The rules for policies, the same in every storage implementation: what a valid policy and name are, when a
 * write is refused as stale, when a write takes a new revision and is snapshotted, that managed and default
 * policies cannot be changed or deleted, which deleted policies are listed, cache eviction, and what is audited.
 * An implementation stores the policies, their revision counters, and makes the conditional writes atomic.
 */
public abstract class StoredPolicyDataService implements PolicyDataService {

    private static final Logger LOGGER = LogManager.getLogger(PolicyDataService.class);

    public static final String INVALID_POLICY_NAME_MESSAGE = "The policy name must only contain letters, numbers, dashes, and underscores.";

    protected final AuditEventPublisher auditEventPublisher;
    private final Gson gson;
    private final PolicyVersionDataService policyVersionDataService;

    // Evicted on every write, so an edit governs the next redaction.
    private final RedactionCache redactionCache;

    protected StoredPolicyDataService(final AuditEventPublisher auditEventPublisher, final Gson gson,
                                      final PolicyVersionDataService policyVersionDataService,
                                      final RedactionCache redactionCache) {
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
        this.policyVersionDataService = policyVersionDataService;
        this.redactionCache = redactionCache;
    }

    /**
     * Reserves the next revision of the owner's policy with this name, at least {@code minimum}, and returns
     * it. Revisions survive deletion and reuse of the name, and concurrent calls never get the same one; gaps
     * are expected.
     */
    protected abstract int reserveRevision(final String userId, final String name, final int minimum);

    /**
     * Stores a new policy and returns its id.
     *
     * @throws DuplicateRecordException when the owner already has a policy with its name.
     */
    protected abstract String insert(final PolicyEntity policy);

    /**
     * Replaces the stored policy only if it is still at {@code expectedRevision} and its description and notes are
     * still at {@code expectedDetailsRevision}, and returns whether it did.
     */
    protected abstract boolean replaceIfUnchanged(final PolicyEntity policy, final int expectedRevision,
            final int expectedDetailsRevision);

    /** Replaces the stored policy only if it is still at {@code expectedRevision}, and returns whether it did. */
    protected abstract boolean replaceIfAtRevision(final PolicyEntity policy, final int expectedRevision);

    /**
     * Sets the description and notes that are not null, and the last-updated time, and advances the details
     * revision, only if the stored policy still has the given policy's revision and details revision, and returns
     * whether it did.
     */
    protected abstract boolean updateDetailsIfUnchanged(final PolicyEntity policy, final String description,
            final String notes, final Date now);

    /** Deletes the owner's policy with this name, not a managed one, and returns whether it did. */
    protected abstract boolean delete(final String userId, final String name);

    /** The managed policy with this name, or null. */
    protected abstract PolicyEntity findManaged(final String name);

    /** The names of the owner's policies, not managed ones. */
    protected abstract Set<String> findNames(final String userId);

    @Override
    public String save(final PolicyEntity policy) {
        policy.setRevision(reserveRevision(policy.getUserId(), policy.getName(), 0));
        // Applied once the insert lands: a create that loses a race for the name leaves no version.
        policyVersionDataService.snapshotUnapplied(policy);
        final String id = insert(policy);
        policyVersionDataService.markApplied(policy.getName(), policy.getUserId(), policy.getRevision());
        return id;
    }

    @Override
    public ServiceResponse update(final String requestId, final String userId, final String policyId, final String policyJson, final String policyDescription, final String policyNotes, final String source) {
        return update(requestId, userId, policyId, policyJson, policyDescription, policyNotes, source, null);
    }

    @Override
    public ServiceResponse update(final String requestId, final String userId, final String policyId, final String policyJson, final String policyDescription, final String policyNotes, final String source, final String authorId) {
        // Here a blank description or notes keeps the current one, and a long one is cut to the limit.
        return replace(requestId, userId, policyId, policyJson, keptIfBlank(policyDescription, POLICY_DESCRIPTION_MAX_LENGTH),
                keptIfBlank(policyNotes, POLICY_NOTES_MAX_LENGTH), source, authorId, null).response();
    }

    @Override
    public WriteOutcome replace(final String requestId, final String userId, final String policyId, final String policyJson,
                                final String description, final String notes, final String source, final String authorId,
                                final String expectedETag) {

        final ServiceResponse tooLong = detailLengthProblem(description, notes);
        if (tooLong != null) {
            return WriteOutcome.refused(tooLong);
        }

        final PolicyEntity policyEntity = findOneById(policyId, userId);

        // Make sure the policy exists.
        if(policyEntity == null) {
            return WriteOutcome.refused(new ServiceResponse("Policy does not exist.", false, 404));
        }

        // Make sure the policy is not managed.
        if(policyEntity.isManaged()) {
            return WriteOutcome.refused(new ServiceResponse("You cannot update a managed policy.", false, 409, REASON_POLICY_MANAGED));
        }

        final ServiceResponse stale = staleRefusal(policyEntity, expectedETag);
        if (stale != null) {
            return WriteOutcome.refused(stale);
        }

        // The policy must be validated to ensure it is syntactically correct and does not contain any invalid values.
        final PolicyValidation validation = validatePolicy(policyJson);
        if (!validation.isValid()) {
            return WriteOutcome.refused(new ServiceResponse(validation.getMessage(), false, 400));
        }

        // The write below only lands if none of these changed since the policy was read.
        final int expectedRevision = policyEntity.getRevision();
        final int expectedDetailsRevision = policyEntity.getDetailsRevision();

        // Metadata-only saves retain the current content revision.
        final boolean contentChanged = !Objects.equals(
                PolicyVersionDataService.contentHash(policyEntity.getPolicy()),
                PolicyVersionDataService.contentHash(policyJson));

        if (contentChanged) {
            policyEntity.setRevision(reserveRevision(policyEntity.getUserId(), policyEntity.getName(), expectedRevision + 1));
        }

        policyEntity.setPolicy(policyJson);
        policyEntity.setLastUpdatedTimestamp(new Date());

        final List<String> detailsChanged = new ArrayList<>();
        if (description != null) {
            policyEntity.setDescription(description);
            detailsChanged.add("description");
        }
        if (notes != null) {
            policyEntity.setNotes(notes);
            detailsChanged.add("notes");
        }
        if (!detailsChanged.isEmpty()) {
            policyEntity.setDetailsRevision(expectedDetailsRevision + 1);
        }

        PolicyKeyWarnings.warnAboutLiteralKeys(policyEntity.getName(), policyJson);

        policyEntity.setAuthorId(authorId);
        // Before the write, so a live revision always has its snapshot; applied only once the write lands.
        policyVersionDataService.snapshotUnapplied(policyEntity);
        if (!replaceIfUnchanged(policyEntity, expectedRevision, expectedDetailsRevision)) {
            return WriteOutcome.refused(new ServiceResponse("Policy changed concurrently. Reload and retry.", false, 409, REASON_POLICY_CHANGED));
        }
        markApplied(policyEntity, expectedRevision);

        redactionCache.evictPolicy(userId, policyEntity.getName());

        if (detailsChanged.isEmpty()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_UPDATED, policyEntity.getId(), source);
        } else {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_UPDATED, policyEntity.getId(), null, source,
                    "details: " + String.join(", ", detailsChanged));
        }

        return new WriteOutcome(new ServiceResponse("The policy was updated.", true, 200), policyEntity);

    }

    /** What {@link #update} sets for a description or notes: null (keep the current one) when blank, else cut to {@code max}. */
    private static String keptIfBlank(final String value, final int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** A 400 for a description or notes over its limit, or null when both fit. */
    private static ServiceResponse detailLengthProblem(final String description, final String notes) {
        if (description != null && description.length() > POLICY_DESCRIPTION_MAX_LENGTH) {
            return new ServiceResponse("The description cannot be longer than " + POLICY_DESCRIPTION_MAX_LENGTH + " characters.", false, 400);
        }
        if (notes != null && notes.length() > POLICY_NOTES_MAX_LENGTH) {
            return new ServiceResponse("The notes cannot be longer than " + POLICY_NOTES_MAX_LENGTH + " characters.", false, 400);
        }
        return null;
    }

    /**
     * Marks the written revision's snapshot applied, and the revision it replaced, which was live: that
     * one is unmarked if Philter stopped between an earlier write and its marking.
     */
    private void markApplied(final PolicyEntity written, final int replacedRevision) {
        policyVersionDataService.markApplied(written.getName(), written.getUserId(), written.getRevision());
        policyVersionDataService.markApplied(written.getName(), written.getUserId(), replacedRevision);
    }

    /** A 409 when {@code expectedETag} is given and is not the policy's current one, otherwise null. */
    private static ServiceResponse staleRefusal(final PolicyEntity current, final String expectedETag) {
        if (expectedETag == null || expectedETag.equals(PolicyDataService.eTag(current))) {
            return null;
        }
        return new ServiceResponse("The policy changed since it was read. Read it again and retry.", false, 409,
                REASON_POLICY_CHANGED);
    }

    @Override
    public ServiceResponse create(final String requestId, final String userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source) {
        return create(requestId, userId, policyJson, policyDescription, policyNotes, policyName, source, null);
    }

    @Override
    public ServiceResponse create(final String requestId, final String userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source, final String authorId) {
        return createPolicy(requestId, userId, policyJson, policyDescription, policyNotes, policyName, source, authorId).response();
    }

    @Override
    public WriteOutcome createPolicy(final String requestId, final String userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source, final String authorId) {

        // Make sure the policy name is not empty.
        if (policyName == null || policyName.isEmpty()) {
            return WriteOutcome.refused(new ServiceResponse("Policy name cannot be empty.", false, 400));
        }

        // Make sure the policy name does not start with `managed_`
        if (policyName.startsWith(MANAGED_NAME_PREFIX)) {
            return WriteOutcome.refused(new ServiceResponse("The policy name cannot start with managed_", false, 400));
        }

        // Make sure the policy name is less than 50 characters long.
        if (policyName.length() > POLICY_NAME_MAX_LENGTH) {
            return WriteOutcome.refused(new ServiceResponse("The policy name cannot be longer than " + POLICY_NAME_MAX_LENGTH + " characters.", false, 400));
        }

        // Make sure the policy name matches the regular expression.
        if (!policyName.matches(POLICY_NAME_REGEX)) {
            return WriteOutcome.refused(new ServiceResponse(INVALID_POLICY_NAME_MESSAGE, false, 400));
        }

        // Make sure the policy name is not one the API's paths use for something else.
        if (RESERVED_POLICY_NAMES.contains(policyName)) {
            return WriteOutcome.refused(new ServiceResponse("The policy name " + policyName + " is reserved.", false, 400));
        }

        // Make sure the policy name is unique.
        if (!isPolicyNameUnique(policyName, userId)) {
            return WriteOutcome.refused(new ServiceResponse("A policy with this name already exists.", false, 409, REASON_POLICY_EXISTS));
        }

        // The policy must be validated to ensure it is syntactically correct and does not contain any invalid values.
        // Once validation is successful, it can be saved.
        final PolicyValidation validation = validatePolicy(policyJson);

        if(!validation.isValid()) {

            return WriteOutcome.refused(new ServiceResponse(validation.getMessage(), false, 400));

        } else {

            PolicyKeyWarnings.warnAboutLiteralKeys(policyName, policyJson);

            final PolicyEntity policyEntity = new PolicyEntity();
            policyEntity.setUserId(userId);
            policyEntity.setAuthorId(authorId);
            policyEntity.setPolicy(policyJson);
            policyEntity.setName(policyName);
            policyEntity.setCreatedTimestamp(new Date());
            policyEntity.setLastUpdatedTimestamp(new Date());

            // Truncate the policy notes.
            if (policyNotes != null && !policyNotes.isEmpty()) {

                if (policyNotes.length() > POLICY_NOTES_MAX_LENGTH) {
                    final String truncatedNotes = policyNotes.substring(0, 1000);
                    policyEntity.setNotes(truncatedNotes);
                } else {
                    policyEntity.setNotes(policyNotes);
                }

            }

            if (policyDescription != null && !policyDescription.isEmpty()) {

                if (policyDescription.length() > POLICY_DESCRIPTION_MAX_LENGTH) {
                    final String truncatedDescription = policyDescription.substring(0, POLICY_DESCRIPTION_MAX_LENGTH);
                    policyEntity.setDescription(truncatedDescription);
                } else {
                    policyEntity.setDescription(policyDescription);
                }

            }

            final String policyId;
            try {
                policyId = save(policyEntity);
            } catch (final DuplicateRecordException race) {
                // A concurrent create took the name after the check above.
                return WriteOutcome.refused(new ServiceResponse("A policy with this name already exists.", false, 409, REASON_POLICY_EXISTS));
            }

            policyEntity.setId(policyId);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_CREATED, policyId, source);

            return new WriteOutcome(new ServiceResponse("The policy was created.", true, policyId, 200), policyEntity);

        }

    }

    @Override
    public PolicyValidation validatePolicy(final String policyJson) {

        LOGGER.debug("Validating the received policy.");

        try {

            // Policies are authored and stored in the native Phileas policy format. Parse to a JSON
            // object first (this also rejects malformed JSON), then confirm the policy actually enables
            // at least one type of sensitive information to detect.
            final JsonObject policyObject = gson.fromJson(policyJson, JsonObject.class);

            if(policyObject == null) {
                LOGGER.warn("Policy validation failed: empty policy.");
                return PolicyValidation.invalid("The policy is empty.");
            }

            final JsonElement identifiers = policyObject.get("identifiers");
            if(identifiers == null || !identifiers.isJsonObject() || identifiers.getAsJsonObject().isEmpty()) {
                LOGGER.warn("Policy validation failed: no identifiers.");
                return PolicyValidation.invalid("The policy must contain an 'identifiers' object describing the information to redact.");
            }

            // Confirm the policy is structurally compatible with the native Phileas policy model.
            final Policy policy = gson.fromJson(policyJson, Policy.class);

            // A ph-eye modelPath may name a model in PHEYE_MODELS_DIRECTORY, as redaction resolves it.
            try {
                PhEyeModels.shared().resolveNames(policy);
            } catch (final PolicyResolutionException ex) {
                LOGGER.warn("Policy validation failed: it names a model that does not exist.");
                return PolicyValidation.invalid(ex.getMessage());
            }

            // And that Phileas will load it: Phileas checks conditions, patterns, and some values only when it
            // builds the filters, so a policy it refuses would otherwise be saved and fail every redaction.
            final String refusal = PolicyLoadCheck.shared().refusal(policy);
            if (refusal != null) {
                LOGGER.warn("Policy validation failed: Phileas refuses to load the policy.");
                return PolicyValidation.invalid(refusal);
            }

            LOGGER.info("Policy validation successful.");
            return PolicyValidation.valid("Policy is valid");

        } catch (final Exception ex) {

            // The policy json is not valid and could not be deserialized.
            LOGGER.warn("Policy validation failed: Policy contains syntax errors.");
            return PolicyValidation.invalid("Policy contains syntax errors.");

        }

    }

    @Override
    public boolean isPolicyNameUnique(final String name, final String userId) {

        return findOne(name, userId) == null;

    }

    @Override
    public PolicyEntity findOneOrManaged(final String name, final String userId) {
        if (PolicyDataService.isManagedName(name)) {
            return findManaged(name);
        }
        return findOne(name, userId);
    }

    @Override
    public ServiceResponse updateDetails(final String requestId, final String userId, final String name,
                                         final String description, final String notes, final String source) {
        return updateDetails(requestId, userId, name, description, notes, source, null).response();
    }

    @Override
    public WriteOutcome updateDetails(final String requestId, final String userId, final String name,
                                      final String description, final String notes, final String source,
                                      final String expectedETag) {

        final ServiceResponse tooLong = detailLengthProblem(description, notes);
        if (tooLong != null) {
            return WriteOutcome.refused(tooLong);
        }

        final PolicyEntity policyEntity = findOne(name, userId);
        if (policyEntity == null) {
            return WriteOutcome.refused(new ServiceResponse("Policy does not exist.", false, 404));
        }

        final ServiceResponse stale = staleRefusal(policyEntity, expectedETag);
        if (stale != null) {
            return WriteOutcome.refused(stale);
        }

        final List<String> changed = new ArrayList<>();
        if (description != null) {
            changed.add("description");
        }
        if (notes != null) {
            changed.add("notes");
        }
        if (changed.isEmpty()) {
            return new WriteOutcome(ServiceResponse.success(), policyEntity);
        }
        final Date now = new Date();

        // Lands only if the policy and its details are as they were read, so a write between the read and
        // this one is not overwritten.
        final boolean written = updateDetailsIfUnchanged(policyEntity, description, notes, now);
        if (!written) {
            return WriteOutcome.refused(new ServiceResponse("Policy changed concurrently. Reload and retry.", false, 409,
                    REASON_POLICY_CHANGED));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_UPDATED, policyEntity.getId(), null, source,
                "details: " + String.join(", ", changed));

        if (description != null) {
            policyEntity.setDescription(description);
        }
        if (notes != null) {
            policyEntity.setNotes(notes);
        }
        policyEntity.setDetailsRevision(policyEntity.getDetailsRevision() + 1);
        policyEntity.setLastUpdatedTimestamp(now);
        return new WriteOutcome(ServiceResponse.success(), policyEntity);

    }

    @Override
    public ServiceResponse rollback(final String requestId, final String policyName,
                                    final String userId, final int targetRevision,
                                    final String principalId, final String clientIpAddress) {
        return rollback(requestId, policyName, userId, targetRevision, principalId, clientIpAddress, null).response();
    }

    @Override
    public WriteOutcome rollback(final String requestId, final String policyName,
                                 final String userId, final int targetRevision,
                                 final String principalId, final String clientIpAddress,
                                 final String expectedETag) {

        // Checked by name, before the lookup: managed policies have no owner, so the per-user lookup below
        // never finds one, and checking the record instead would answer 404.
        if (PolicyDataService.isManagedName(policyName)) {
            return WriteOutcome.refused(new ServiceResponse("Managed policies cannot be rolled back.", false, 409, REASON_POLICY_MANAGED));
        }

        final PolicyEntity live = findOne(policyName, userId);
        if (live == null) {
            return WriteOutcome.refused(new ServiceResponse("Policy does not exist.", false, 404));
        }

        final PolicyVersionEntity targetVersion =
                policyVersionDataService.findByNameAndRevision(policyName, userId, targetRevision);
        if (targetVersion == null) {
            return WriteOutcome.refused(new ServiceResponse("Revision " + targetRevision + " does not exist.", false, 404));
        }

        final ServiceResponse stale = staleRefusal(live, expectedETag);
        if (stale != null) {
            return WriteOutcome.refused(stale);
        }

        // An earlier revision may be one this release of Phileas refuses to load, so it is checked as a new
        // policy would be rather than restored as it was.
        final PolicyValidation validation = validatePolicy(targetVersion.getPolicy());
        if (!validation.isValid()) {
            return WriteOutcome.refused(new ServiceResponse("Revision " + targetRevision + " cannot be restored: "
                    + validation.getMessage(), false, 400));
        }

        final int expectedRevision = live.getRevision();
        live.setPolicy(targetVersion.getPolicy());
        live.setRevision(reserveRevision(live.getUserId(), live.getName(), expectedRevision + 1));
        live.setLastUpdatedTimestamp(new Date());

        final int newRevision = live.getRevision();
        live.setAuthorId(principalId);
        policyVersionDataService.snapshotUnapplied(live);
        if (!replaceIfUnchanged(live, expectedRevision, live.getDetailsRevision())) {
            return WriteOutcome.refused(new ServiceResponse("Policy changed concurrently. Reload and retry.", false, 409, REASON_POLICY_CHANGED));
        }
        markApplied(live, expectedRevision);

        redactionCache.evictPolicy(userId, policyName);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_ROLLED_BACK, principalId, live.getId(),
                clientIpAddress, "policy: " + AuditDetails.quote(policyName) + ", rolled back to revision: " + targetRevision
                        + ", new revision: " + newRevision);

        return new WriteOutcome(new ServiceResponse("Policy rolled back to revision " + targetRevision
                + ". New revision: " + newRevision, true, 200), live);
    }

    @Override
    public ServiceResponse deleteByName(final String requestId, final String policyName, final String userId,
                                        final Source source, final String principalId, final String clientIpAddress) {

        // By name, before the lookup, as in rollback: a managed policy has no owner to find it under.
        if (PolicyDataService.isManagedName(policyName)) {
            return new ServiceResponse("Managed policies cannot be deleted.", false, 409, REASON_POLICY_MANAGED);
        }

        final PolicyEntity policyEntity = findOne(policyName, userId);

        if(policyEntity == null) {
            return new ServiceResponse("Policy does not exist.", false, 404);
        }

        if(DEFAULT_POLICY_NAME.equalsIgnoreCase(policyName)) {
            return new ServiceResponse("Cannot delete the default policy.", false, 409, REASON_POLICY_DEFAULT);
        }

        if(delete(userId, policyName)) {

            redactionCache.evictPolicy(userId, policyName);
            policyVersionDataService.recordDeletion(userId, policyName, policyEntity.getRevision(), principalId, new Date());

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_DELETED, principalId, policyEntity.getId(),
                    clientIpAddress, "policy: " + AuditDetails.quote(policyName) + ", source: " + source.getSource());

            return new ServiceResponse("Policy deleted.", true, 200);

        } else {

            return new ServiceResponse("Policy does not exist.", false, 404);

        }

    }

    @Override
    public Listings.Page<DeletedPolicy> findDeleted(final String userId, final String q, final boolean descending,
                                                    final int offset, final int limit) {

        final java.util.Set<String> live = findNames(userId);

        final String search = q == null || q.isBlank() ? null : q.trim().toLowerCase(java.util.Locale.ROOT);
        final java.util.Comparator<String> byName = descending ? java.util.Comparator.reverseOrder() : java.util.Comparator.naturalOrder();
        final List<String> matching = policyVersionDataService.findNames(userId).stream()
                .filter(name -> !live.contains(name))
                .filter(name -> search == null || name.toLowerCase(java.util.Locale.ROOT).contains(search))
                .sorted(byName)
                .toList();

        final List<DeletedPolicy> deleted = new ArrayList<>();
        for (final String name : matching.stream().skip(offset).limit(limit).toList()) {
            final List<PolicyVersionEntity> latest = policyVersionDataService.findAllByName(name, userId, 0, 1);
            final int latestRevision = latest.isEmpty() ? 0 : latest.get(0).getRevision();
            final PolicyVersionDataService.Deletion deletion = policyVersionDataService.findDeletion(userId, name);
            deleted.add(new DeletedPolicy(name, latestRevision,
                    deletion == null ? null : deletion.deletedAt(),
                    deletion == null ? null : deletion.deletedBy()));
        }
        return new Listings.Page<>(deleted, matching.size());

    }

    @Override
    public void loadAndSaveManagedPolicies() {

        LOGGER.info("Loading managed policies from resource files...");

        final ManagedPolicyLoader loader = new ManagedPolicyLoader(gson);
        final List<PolicyEntity> managedPolicies = loader.loadManagedPolicies();

        for (final PolicyEntity managedPolicy : managedPolicies) {

            final PolicyEntity existingPolicy = findManaged(managedPolicy.getName());

            if (existingPolicy != null) {

                // Update existing managed policy
                final int expectedRevision = existingPolicy.getRevision();
                existingPolicy.setPolicy(managedPolicy.getPolicy());
                existingPolicy.setDescription(managedPolicy.getDescription());
                existingPolicy.setDetailsRevision(existingPolicy.getDetailsRevision() + 1);
                existingPolicy.setLastUpdatedTimestamp(new Date());
                existingPolicy.setRevision(reserveRevision(existingPolicy.getUserId(), existingPolicy.getName(), expectedRevision + 1));

                policyVersionDataService.snapshotUnapplied(existingPolicy);
                if (!replaceIfAtRevision(existingPolicy, expectedRevision)) {
                    throw new IllegalStateException("Managed policy changed concurrently; restart initialization.");
                }
                markApplied(existingPolicy, expectedRevision);
                LOGGER.info("Updated managed policy: {}", managedPolicy.getName());

            } else {

                // Insert new managed policy
                final String managedId = save(managedPolicy);
                managedPolicy.setId(managedId);
                LOGGER.info("Inserted managed policy: {}", managedPolicy.getName());

            }

        }

        LOGGER.info("Managed policies loaded successfully.");

    }

}
