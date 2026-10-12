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

import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.JsonArray;

import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Each user's redaction policies, and the managed policies every user can use.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A policy name is unique per user: creating a second policy with the same name is refused and writes
 *   nothing.</li>
 *   <li>Conditional writes: given an expected entity tag ({@link #eTag}), {@link #replace} and the other
 *   conditional writes change the policy only if its content, description, and notes are still as they were
 *   when the tag was read. Otherwise nothing is written and the refusal's reason is
 *   {@link #REASON_POLICY_CHANGED}. Of two writes racing with the same tag, exactly one succeeds.</li>
 *   <li>Revision numbers only increase and are never reused for a policy, even after it is deleted and
 *   created again.</li>
 *   <li>Each content change is snapshotted with {@link PolicyVersionDataService} before the policy changes, so the
 *   revision a ledger entry names can always be resolved to its content.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping policies, sort by the
 *   fields the API offers, and search names as case-insensitive plain text (see {@link Listings}).</li>
 * </ul>
 */
public interface PolicyDataService {

    String POLICY_NAME_REGEX = "^[a-zA-Z0-9_-]+$";

    int POLICY_NAME_MAX_LENGTH = 50;

    int POLICY_NOTES_MAX_LENGTH = 1000;

    int POLICY_DESCRIPTION_MAX_LENGTH = 200;

    /** The prefix of every managed policy's name, which a user's policy cannot start with. */
    String MANAGED_NAME_PREFIX = "managed_";

    /** The policy every user is given, which cannot be deleted. */
    String DEFAULT_POLICY_NAME = "default";

    /**
     * Names a policy cannot have, because a request path with them means something else:
     * {@code /api/policies/templates/...} is where policy templates are read.
     */
    List<String> RESERVED_POLICY_NAMES = List.of("templates");

    /** Reasons a policy change is refused with 409, carried in the response's details. */
    String REASON_POLICY_MANAGED = "policy_managed";

    String REASON_POLICY_CHANGED = "policy_changed";

    String REASON_POLICY_EXISTS = "policy_exists";

    String REASON_POLICY_DEFAULT = "policy_default";

    /**
     * Updates an existing policy.
     * @param policyId The ID of the policy to update.
     * @param policyJson The body of the policy.
     * @param policyDescription The description of the policy.
     * @param policyNotes The notes of the policy.
     * @param source The source of the change
     * @return A {@link ServiceResponse} indicating the result of the operation.
     */
    ServiceResponse update(final String requestId, final String userId, final String policyId, final String policyJson, final String policyDescription, final String policyNotes, final String source);

    /**
     * As above, recording {@code authorId} as the author of the new revision. It is the caller, not
     * necessarily {@code userId}: an admin may change another user's policy.
     */
    ServiceResponse update(final String requestId, final String userId, final String policyId, final String policyJson, final String policyDescription, final String policyNotes, final String source, final String authorId);

    /**
     * Replaces a policy's content as a new revision, and its description and notes in the same write. A null
     * description or notes keeps the current one; any other value, empty included, is set as given. Values over
     * the length limits are refused.
     *
     * @param expectedETag The {@link #eTag} the caller read, or null to skip the check. When it is not the
     *                     policy's current one, nothing is written and the refusal's reason is
     *                     {@link #REASON_POLICY_CHANGED}.
     */
    WriteOutcome replace(final String requestId, final String userId, final String policyId, final String policyJson,
            final String description, final String notes, final String source, final String authorId,
            final String expectedETag);

    /**
     * The outcome of a policy write and, when it succeeded, the policy as this write left it, so a caller can
     * report the {@link #eTag} its own write produced rather than one a later write produced.
     */
    record WriteOutcome(ServiceResponse response, PolicyEntity policy) {

        public static WriteOutcome refused(final ServiceResponse response) {
            return new WriteOutcome(response, null);
        }

    }

    /** The shape of an {@link #eTag}: the revision, a dash, and 16 hex digits. */
    Pattern ETAG_PATTERN = Pattern.compile("[0-9]+-[0-9a-f]{16}");

    /**
     * The policy's entity tag: its revision and a hash of its description and notes, so it changes whenever
     * the policy or its details change. The revision alone does not: editing only the details keeps it.
     */
    static String eTag(final PolicyEntity policy) {
        final JsonArray details = new JsonArray();
        details.add(policy.getDescription());
        details.add(policy.getNotes());
        return policy.getRevision() + "-" + EncryptionService.hashSha256(details.toString()).substring(0, 16);
    }

    ServiceResponse create(final String requestId, final String userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source);

    /** As above, recording {@code authorId}, the caller, as the author of the policy's first revision. */
    ServiceResponse create(final String requestId, final String userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source, final String authorId);

    /** As above, returning the policy as created, for its {@link #eTag}. */
    WriteOutcome createPolicy(final String requestId, final String userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source, final String authorId);

    boolean containsValidChoice(final String value, final List<String> allowedValues);

    PolicyValidation validatePolicy(final String policyJson);

    List<PolicyEntity> findManagedPolicies();

    /** Returns one page of managed policies, ordered by name (for paging the Managed Policies grid). */
    List<PolicyEntity> findManagedPolicies(final int offset, final int limit);

    /** A page of the user's own policies whose names contain {@code q} (any, when null), and their total. */
    Listings.Page<PolicyEntity> listOwn(final String userId, final String q, final Listings.Sort sort,
            final int offset, final int limit);

    /** A page of every user's policies, managed ones excluded, whose names contain {@code q}, and their total. */
    Listings.Page<PolicyEntity> listAcrossUsers(final String q, final Listings.Sort sort, final int offset,
            final int limit);

    /** A page of the managed policies whose names contain {@code q}, and their total. */
    Listings.Page<PolicyEntity> listManaged(final String q, final Listings.Sort sort, final int offset,
            final int limit);

    /** Returns the total number of managed policies (for paging). */
    int countManagedPolicies();

    List<PolicyEntity> find(final String userId, final String searchTerm);

    /**
     * Returns every policy for every user. Intended for admin-only views; ordinary access must use the
     * owner-scoped {@link #findAll(String, int, int, boolean)}.
     */
    List<PolicyEntity> findAllAcrossUsers();

    /**
     * Returns one page of policies across every user, ordered by name. Intended for admin-only views;
     * ordinary access must use the owner-scoped {@link #findAll(String, int, int, boolean)}.
     */
    List<PolicyEntity> findAllAcrossUsers(final int offset, final int limit);

    /**
     * Returns one page of every user's policies, ordered by name and then id, so the order is stable
     * across pages even though many users have a policy of the same name. Managed policies are left out
     * unless {@code includeManaged}.
     */
    List<PolicyEntity> findAllAcrossUsers(final int offset, final int limit, final boolean includeManaged);

    int countAllAcrossUsers();

    int countAllAcrossUsers(final boolean includeManaged);

    /**
     * Finds all policies for a user with pagination support.
     * @param offset The offset for pagination.
     * @param limit The maximum number of policies to return.
     * @param includeManagedPolicies Whether to include managed policies in the result.
     * @return A list of {@link PolicyEntity} objects.
     */
    List<PolicyEntity> findAll(final String userId, final int offset, final int limit, final boolean includeManagedPolicies);

    List<PolicyEntity> findAll(final String userId, final int offset, final int limit, final boolean includeManagedPolicies, final String sortField, final boolean ascending);

    /**
     * Counts the total number of policies for a user (excludes managed policies).
     * @return The count of policies.
     */
    int count(final String userId);

    boolean isPolicyNameUnique(final String name, final String userId);

    PolicyEntity findOneById(final String id, final String userId);

    /** Whether a name belongs to a managed policy; user policies cannot start with this prefix. */
    static boolean isManagedName(final String name) {
        return name != null && name.startsWith(MANAGED_NAME_PREFIX);
    }

    /**
     * The user's policy with this name or, for a {@code managed_} name, the managed policy. Managed
     * names cannot be taken by a user's policy, so the two never collide.
     */
    PolicyEntity findOneOrManaged(final String name, final String userId);

    /**
     * Sets a policy's description and notes, which are not part of the policy or its versions. A null
     * value is left as it is and an empty one clears it. Values over the length limits are refused.
     */
    ServiceResponse updateDetails(final String requestId, final String userId, final String name,
            final String description, final String notes, final String source);

    /**
     * As above, refused with {@link #REASON_POLICY_CHANGED} when {@code expectedETag} is given and is not the
     * policy's current {@link #eTag}. Returns the policy as this write left it.
     */
    WriteOutcome updateDetails(final String requestId, final String userId, final String name,
            final String description, final String notes, final String source,
            final String expectedETag);

    PolicyEntity findOne(final String name, final String userId);

    ServiceResponse duplicatePolicy(final String requestId, final String userId, final String name, final String newName, String source);

    /**
     * Restores a prior revision of a policy as a new revision. History is never rewritten: the target
     * revision's content becomes the new head revision, and a snapshot of that new head is retained.
     *
     * @param requestId       Correlation ID for audit events.
     * @param policyName      Name of the policy to roll back.
     * @param userId          Owner of the policy.
     * @param targetRevision  The revision whose content should become the new head.
     * @param principalId     The caller, recorded in the audit event. Not necessarily {@code userId}:
     *                        an admin may roll back another user's policy.
     * @param clientIpAddress The client IP address, or null when unavailable.
     * @return A {@link ServiceResponse} whose {@code object} field carries the new revision number on
     *         success (HTTP 200), or an error code on failure.
     */
    ServiceResponse rollback(final String requestId, final String policyName,
            final String userId, final int targetRevision,
            final String principalId, final String clientIpAddress);

    /**
     * As above, refused with {@link #REASON_POLICY_CHANGED} when {@code expectedETag} is given and is not the
     * policy's current {@link #eTag}. Returns the policy as this rollback left it.
     */
    WriteOutcome rollback(final String requestId, final String policyName,
            final String userId, final int targetRevision,
            final String principalId, final String clientIpAddress,
            final String expectedETag);

    /**
     * Deletes a policy by name.
     *
     * @param requestId       Correlation ID for audit events.
     * @param policyName      Name of the policy to delete.
     * @param userId          Owner of the policy.
     * @param source          Where the deletion came from, such as the API.
     * @param principalId     The caller, recorded in the audit event. Not necessarily {@code userId}:
     *                        an admin may delete another user's policy.
     * @param clientIpAddress The client IP address, or null when unavailable.
     * @return A {@link ServiceResponse} indicating the result of the operation.
     */
    ServiceResponse deleteByName(final String requestId, final String policyName, final String userId,
            final Source source, final String principalId, final String clientIpAddress);

    /** A policy that was deleted but whose version history is kept. */
    record DeletedPolicy(String name, int latestRevision, Date deletedAt, String deletedBy) {
    }

    /**
     * A page of the user's deleted policies that still have version history, by name. A policy is
     * deleted when it has retained versions and no live policy has its name. When and by whom it was
     * deleted are {@code null} for a deletion made before deletions were recorded.
     */
    Listings.Page<DeletedPolicy> findDeleted(final String userId, final String q, final boolean descending,
            final int offset, final int limit);

    long deleteAll();

    /**
     * Load managed policies from JSON files and save them to the database.
     * This method should be called during application initialization.
     * Existing managed policies with the same name will be updated.
     */
    void loadAndSaveManagedPolicies();

    String save(PolicyEntity policy);

    void update(PolicyEntity policy);

}
