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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CreditCard;
import ai.philterd.phileas.policy.filters.EmailAddress;
import ai.philterd.phileas.policy.filters.Ssn;
import ai.philterd.phileas.policy.filters.ZipCode;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.policies.ManagedPolicyLoader;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

public class PolicyDataService extends AbstractService<PolicyEntity> {

    private static final Logger LOGGER = LogManager.getLogger(PolicyDataService.class);

    public static final String POLICY_NAME_REGEX = "^[a-zA-Z0-9_-]+$";
    public static final int MAX_NUMBER_OF_POLICIES = 50;
    public static final int POLICY_NAME_MAX_LENGTH = 50;
    public static final int POLICY_NOTES_MAX_LENGTH = 1000;
    public static final int POLICY_DESCRIPTION_MAX_LENGTH = 200;
    public static final String INVALID_POLICY_NAME_MESSAGE = "The policy name must only contain letters, numbers, dashes, and underscores.";
    public static final int MAX_LIMIT = 100;

    private final Gson gson;
    private final PolicyVersionDataService policyVersionDataService;

    public PolicyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final Gson gson, final PolicyVersionDataService policyVersionDataService) {
        super(mongoClient, "policies", auditEventPublisher);
        this.gson = gson;
        this.policyVersionDataService = policyVersionDataService;

        // User policies are listed/looked up by (user_id, name); managed policies by (managed, name).
        ensureIndex(Indexes.ascending("user_id", "name"));
        ensureIndex(Indexes.ascending("managed", "name"));
        // Listing a user's policies also matches shared policies via an OR branch on shared=true, so
        // index shared so that branch uses an index instead of scanning the collection.
        ensureIndex(Indexes.ascending("shared", "name"));
    }

    /**
     * Updates an existing policy.
     * @param policyId The ID of the policy to update.
     * @param policyJson The body of the policy.
     * @param policyDescription The description of the policy.
     * @param policyNotes The notes of the policy.
     * @param source The source of the change
     * @return A {@link ServiceResponse} indicating the result of the operation.
     */
    public ServiceResponse update(final String requestId, final ObjectId userId, final ObjectId policyId, final String policyJson, final String policyDescription, final String policyNotes, final String source) {

        final PolicyEntity policyEntity = findOneById(policyId, userId);

        // Make sure the policy exists.
        if(policyEntity == null) {
            return new ServiceResponse("Policy does not exist.", false, 404);
        }

        // Make sure the policy is not managed.
        if(policyEntity.isManaged()) {
            return new ServiceResponse("You cannot update a managed policy.", false, 409);
        }

        policyEntity.incrementRevision();
        policyEntity.setPolicy(policyJson);
        policyEntity.setLastUpdatedTimestamp(new Date());

        // Only update the notes when a value is provided. A null or blank value (e.g. an update
        // via the API or the advanced policy editor that omits notes) preserves the existing notes
        // rather than blanking them.
        if(policyNotes != null && !policyNotes.isBlank()) {

            // Truncate the notes to 1000 characters, if needed.
            if(policyNotes.length() > POLICY_NOTES_MAX_LENGTH) {
                final String truncatedNotes = policyNotes.substring(0, POLICY_NOTES_MAX_LENGTH);
                policyEntity.setNotes(truncatedNotes);
            } else {
                policyEntity.setNotes(policyNotes);
            }

        }

        // Only update the description when a value is provided. A null or blank value preserves the
        // existing description rather than blanking it.
        if(policyDescription != null && !policyDescription.isBlank()) {

            // Truncate the description to 200 characters, if needed.
            if(policyDescription.length() > POLICY_DESCRIPTION_MAX_LENGTH) {
                final String truncatedDescription = policyDescription.substring(0, POLICY_DESCRIPTION_MAX_LENGTH);
                policyEntity.setDescription(truncatedDescription);
            } else {
                policyEntity.setDescription(policyDescription);
            }

        }

        // The policy must be validated to ensure it is syntactically correct and does not contain any invalid values.
        // Once validation is successful, it can be saved.
        final PolicyValidation validation = validatePolicy(policyJson);

        if(validation.isValid()) {

            update(policyEntity);

            // Retain an immutable snapshot of the new version as governance evidence.
            policyVersionDataService.snapshot(policyEntity);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_UPDATED, policyEntity.getId(), source);

            return new ServiceResponse("The policy was updated.", true, 200);

        } else {

            return new ServiceResponse(validation.getMessage(), false, 400);

        }

    }

    public ServiceResponse create(final String requestId, final ObjectId userId, final String policyJson, final String policyDescription, final String policyNotes, final String policyName, final String source) {

        // Make sure the policy name is not empty.
        if (policyName == null || policyName.isEmpty()) {
            return new ServiceResponse("Policy name cannot be empty.", false, 400);
        }

        // Make sure the policy name does not start with `managed_`
        if (policyName.startsWith("managed_")) {
            return new ServiceResponse("The policy name cannot start with managed_", false, 400);
        }

        // Make sure the policy name is less than 50 characters long.
        if (policyName.length() > POLICY_NAME_MAX_LENGTH) {
            return new ServiceResponse("The policy name cannot be longer than " + POLICY_NAME_MAX_LENGTH + " characters.", false, 400);
        }

        // Make sure the policy name matches the regular expression.
        if (!policyName.matches(POLICY_NAME_REGEX)) {
            return new ServiceResponse(INVALID_POLICY_NAME_MESSAGE, false, 400);
        }

        // Make sure the policy name is unique.
        if (!isPolicyNameUnique(policyName, userId)) {
            return new ServiceResponse("A policy with this name already exists.", false, 409);
        }

        // The policy must be validated to ensure it is syntactically correct and does not contain any invalid values.
        // Once validation is successful, it can be saved.
        final PolicyValidation validation = validatePolicy(policyJson);

        if(!validation.isValid()) {

            return new ServiceResponse(validation.getMessage(), false, 400);

        } else {

            final PolicyEntity policyEntity = new PolicyEntity();
            policyEntity.setUserId(userId);
            policyEntity.setPolicy(policyJson);
            policyEntity.setName(policyName);
            policyEntity.setCreatedTimestamp(new Date());
            policyEntity.setLastUpdatedTimestamp(new Date());

            // Truncate the policy notes.
            if (policyNotes != null & !policyNotes.isEmpty()) {

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

            final ObjectId policyId = save(policyEntity);

            // Retain an immutable snapshot of the initial version as governance evidence.
            policyEntity.setId(policyId);
            policyVersionDataService.snapshot(policyEntity);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_CREATED, policyId, source);

            return new ServiceResponse("The policy was created.", true, policyId, 200);

        }

    }

    public boolean containsValidChoice(final String value, final List<String> allowedValues) {

        if(value == null) {
            return false;
        }

        return allowedValues.contains(value.toLowerCase());

    }

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
            gson.fromJson(policyJson, Policy.class);

            LOGGER.info("Policy validation successful.");
            return PolicyValidation.valid("Policy is valid");

        } catch (final Exception ex) {

            // The policy json is not valid and could not be deserialized.
            LOGGER.warn("Policy validation failed: Policy contains syntax errors.");
            return PolicyValidation.invalid("Policy contains syntax errors.");

        }

    }

    public void insertDefaultPolicies(final ObjectId userId) {

        final Policy policy = new Policy();
        policy.getIdentifiers().setEmailAddress(new EmailAddress());
        policy.getIdentifiers().setCreditCard(new CreditCard());
        policy.getIdentifiers().setSsn(new Ssn());
        policy.getIdentifiers().setZipCode(new ZipCode());

        final PolicyEntity defaultPolicy = new PolicyEntity();
        defaultPolicy.setName("Default Policy");
        defaultPolicy.setUserId(userId);
        collection.insertOne(defaultPolicy.toDocument());

    }

    public List<PolicyEntity> findManagedPolicies() {

        final FindIterable<Document> documents = collection.find(Filters.eq("managed", true)).sort(Sorts.ascending("name"));

        final List<PolicyEntity> policies = new ArrayList<>();

        for(final Document document : documents) {

            final PolicyEntity policyEntity = PolicyEntity.fromDocument(document);
            policies.add(policyEntity);

        }

        return policies;

    }

    /** Returns one page of managed policies, ordered by name (for paging the Managed Policies grid). */
    public List<PolicyEntity> findManagedPolicies(final int offset, final int limit) {

        final FindIterable<Document> documents = collection.find(Filters.eq("managed", true))
                .sort(Sorts.ascending("name")).skip(offset).limit(limit);

        final List<PolicyEntity> policies = new ArrayList<>();

        for (final Document document : documents) {
            policies.add(PolicyEntity.fromDocument(document));
        }

        return policies;

    }

    /** Returns the total number of managed policies (for paging). */
    public int countManagedPolicies() {
        return (int) collection.countDocuments(Filters.eq("managed", true));
    }

    public List<PolicyEntity> find(final ObjectId userId, final String searchTerm) {

        final Pattern pattern = Pattern.compile(".*" + Pattern.quote(searchTerm) + ".*", Pattern.CASE_INSENSITIVE);

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("managed", false),
                Filters.or(
                        Filters.regex("name", pattern),
                        Filters.regex("description", pattern),
                        Filters.regex("notes", pattern)
                )
        );

        final FindIterable<Document> documents = collection.find(query).sort(Sorts.ascending("name"));

        final List<PolicyEntity> policies = new ArrayList<>();

        for(final Document document : documents) {

            final PolicyEntity policyEntity = PolicyEntity.fromDocument(document);
            policies.add(policyEntity);

        }

        return policies;

    }

    /**
     * Returns every policy for every user. Intended for admin-only views; ordinary access must use the
     * owner-scoped {@link #findAll(ObjectId, int, int, boolean)}.
     */
    public List<PolicyEntity> findAllAcrossUsers() {

        final List<PolicyEntity> policies = new ArrayList<>();

        for (final Document document : collection.find()) {
            policies.add(PolicyEntity.fromDocument(document));
        }

        return policies;

    }

    /**
     * Returns one page of policies across every user, ordered by name. Intended for admin-only views;
     * ordinary access must use the owner-scoped {@link #findAll(ObjectId, int, int, boolean)}.
     */
    public List<PolicyEntity> findAllAcrossUsers(final int offset, final int limit) {

        final List<PolicyEntity> policies = new ArrayList<>();

        for (final Document document : collection.find().sort(Sorts.ascending("name")).skip(offset).limit(limit)) {
            policies.add(PolicyEntity.fromDocument(document));
        }

        return policies;

    }

    /** Returns the total number of policies across every user (for admin paging). */
    public int countAllAcrossUsers() {
        return (int) collection.countDocuments();
    }

    /**
     * Finds all policies for a user with pagination support.
     * @param offset The offset for pagination.
     * @param limit The maximum number of policies to return.
     * @param includeManagedPolicies Whether to include managed policies in the result.
     * @return A list of {@link PolicyEntity} objects.
     */
    public List<PolicyEntity> findAll(final ObjectId userId, final int offset, final int limit, final boolean includeManagedPolicies) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        final Bson query;

        if (userId != null) {
            query = Filters.or(
                    Filters.eq("user_id", userId),
                    Filters.eq("shared", true)
            );
        } else {
            query = new Document();
        }

        final FindIterable<Document> documents = collection.find(query)
                .sort(Sorts.ascending("name"))
                .skip(offset)
                .limit(effectiveLimit);

        final List<PolicyEntity> policies = new ArrayList<>();

        for(final Document document : documents) {

            final PolicyEntity policyEntity = PolicyEntity.fromDocument(document);
            policies.add(policyEntity);

        }

        // The UI sometimes needs to see all policies, including managed policies.
        if(includeManagedPolicies) {

            // Now add the managed policies.
            policies.addAll(findManagedPolicies());

        }

        return policies;

    }

    public List<PolicyEntity> findAll(final ObjectId userId, final int offset, final int limit, final boolean includeManagedPolicies, final String sortField, final boolean ascending) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        // Build the sort criteria
        final Bson sortCriteria = ascending ? Sorts.ascending(sortField) : Sorts.descending(sortField);

        final Bson query;

        if (userId != null) {
            query = Filters.or(
                    Filters.eq("user_id", userId),
                    Filters.eq("shared", true)
            );
        } else {
            query = new Document();
        }

        final FindIterable<Document> documents = collection.find(query)
                .sort(sortCriteria)
                .skip(offset)
                .limit(effectiveLimit);

        final List<PolicyEntity> policies = new ArrayList<>();

        for(final Document document : documents) {

            final PolicyEntity policyEntity = PolicyEntity.fromDocument(document);
            policies.add(policyEntity);

        }

        // The UI sometimes needs to see all policies, including managed policies.
        if(includeManagedPolicies) {

            // Now add the managed policies.
            policies.addAll(findManagedPolicies());

        }

        return policies;

    }

    /**
     * Counts the total number of policies for a user (excludes managed policies).
     * @return The count of policies.
     */
    public int count(final ObjectId userId) {

        final Bson query;

        if (userId != null) {
            query = Filters.and(
                    Filters.eq("managed", false),
                    Filters.eq("user_id", userId)
            );
        } else {
            query = Filters.eq("managed", false);
        }

        final long count = collection.countDocuments(query);

        return (int) count;

    }

    public boolean isPolicyNameUnique(final String name, final ObjectId userId) {

        return findOne(name, userId) == null;

    }

    public PolicyEntity findOneById(final ObjectId id, final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("_id", id),
                Filters.or(
                        Filters.eq("user_id", userId),
                        Filters.eq("shared", true)
                )
        );

        final Document document = collection.find(query).first();

        if(document != null) {

            return PolicyEntity.fromDocument(document);

        } else {

            return null;

        }

    }

    public PolicyEntity findOne(final String name, final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("name", name),
                Filters.eq("user_id", userId)
        );

        final Document document = collection.find(query).first();

        if(document != null) {

            return PolicyEntity.fromDocument(document);

        } else {

            return null;

        }

    }

    public ServiceResponse duplicatePolicy(final String requestId, final ObjectId userId, final String name, final String newName, String source) {

        // Make sure the policy name is not empty.
        if (newName == null || newName.isEmpty()) {
            return new ServiceResponse("Policy name cannot be empty.", false, 400);
        }

        // Make sure the policy name does not start with `managed_`
        if (newName.startsWith("managed_")) {
            return new ServiceResponse("The policy name cannot start with managed_", false, 400);
        }

        // Make sure the policy name is less than 50 characters long.
        if (newName.length() > POLICY_NAME_MAX_LENGTH) {
            return new ServiceResponse("The policy name cannot be longer than 50 characters.", false, 400);
        }

        // Make sure the policy name matches the regular expression.
        if (!newName.matches(POLICY_NAME_REGEX)) {
            return new ServiceResponse("The policy name must only contain letters, numbers, dashes, and underscores.", false, 400);
        }

        // Make sure the new name is unique.
        if(!isPolicyNameUnique(newName, userId)) {
            return new ServiceResponse("A policy with this name already exists.", false, 409);
        }

        final PolicyEntity policyEntity = findOne(name, userId);

        // The source policy must exist.
        if (policyEntity == null) {
            return new ServiceResponse("Policy does not exist.", false, 404);
        }

        policyEntity.setName(newName);
        policyEntity.setId(null);
        policyEntity.setUserId(userId);

        final ObjectId objectId = collection.insertOne(policyEntity.toDocument()).getInsertedId().asObjectId().getValue();

        // Retain an immutable snapshot of the duplicated policy as governance evidence (the content is
        // identical to the source, so this is a no-op when the source was already snapshotted).
        policyEntity.setId(objectId);
        policyVersionDataService.snapshot(policyEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_CREATED, objectId, source);

        return new ServiceResponse("The policy was duplicated.", true, objectId, 200);

    }

    /**
     * Restores a prior revision of a policy as a new revision. History is never rewritten: the target
     * revision's content becomes the new head revision, and a snapshot of that new head is retained.
     *
     * @param requestId      Correlation ID for audit events.
     * @param policyName     Name of the policy to roll back.
     * @param userId         Owner of the policy.
     * @param targetRevision The revision whose content should become the new head.
     * @return A {@link ServiceResponse} whose {@code object} field carries the new revision number on
     *         success (HTTP 200), or an error code on failure.
     */
    public ServiceResponse rollback(final String requestId, final String policyName,
                                    final ObjectId userId, final int targetRevision) {

        final PolicyEntity live = findOne(policyName, userId);
        if (live == null) {
            return new ServiceResponse("Policy does not exist.", false, 404);
        }

        if (live.isManaged()) {
            return new ServiceResponse("Managed policies cannot be rolled back.", false, 409);
        }

        final PolicyVersionEntity targetVersion =
                policyVersionDataService.findByNameAndRevision(policyName, userId, targetRevision);
        if (targetVersion == null) {
            return new ServiceResponse("Revision " + targetRevision + " does not exist.", false, 404);
        }

        live.setPolicy(targetVersion.getPolicy());
        live.incrementRevision();
        live.setLastUpdatedTimestamp(new Date());

        final int newRevision = live.getRevision();
        update(live);
        policyVersionDataService.snapshot(live);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_ROLLED_BACK, null, null,
                "policy: " + policyName + ", rolled back to revision: " + targetRevision
                        + ", new revision: " + newRevision, null);

        return new ServiceResponse("Policy rolled back to revision " + targetRevision
                + ". New revision: " + newRevision, true, 200);
    }

    public ServiceResponse deleteByName(final String requestId, final String policyName, final ObjectId userId, final Source source) {

        if(findOne(policyName, userId) == null) {
            return new ServiceResponse("Policy does not exist.", false, 404);
        }

        if("default".equalsIgnoreCase(policyName)) {
            return new ServiceResponse("Cannot delete the default policy.", false, 409);
        }

        // There should never be a policy that is "managed=true" and has a user_id != null but this is just a safeguard.
        final Document query = new Document("name", policyName).append("managed", false).append("user_id", userId);
        final DeleteResult deleteResult = collection.deleteOne(query);

        if(deleteResult.getDeletedCount() == 1) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_DELETED, null, null,"Policy Name: " + policyName, source.getSource());

            return new ServiceResponse("Policy deleted.", true, 200);

        } else {

            return new ServiceResponse("Policy does not exist.", false, 404);

        }

    }

    public long deleteAll() {

        // There should never be a policy that is "managed=true" and has a user_id != null, but this is just a safeguard.
        final Document query = new Document("managed", false);

        final DeleteResult deleteResult = collection.deleteMany(query);

        return deleteResult.getDeletedCount();

    }

    /**
     * Load managed policies from JSON files and save them to the database.
     * This method should be called during application initialization.
     * Existing managed policies with the same name will be updated.
     */
    public void loadAndSaveManagedPolicies() {

        LOGGER.info("Loading managed policies from resource files...");

        final ManagedPolicyLoader loader = new ManagedPolicyLoader(gson);
        final List<PolicyEntity> managedPolicies = loader.loadManagedPolicies();

        for (final PolicyEntity managedPolicy : managedPolicies) {

            // Check if a managed policy with this name already exists
            final Bson query = Filters.and(
                    Filters.eq("name", managedPolicy.getName()),
                    Filters.eq("managed", true)
            );

            final Document existingDocument = collection.find(query).first();

            if (existingDocument != null) {

                // Update existing managed policy
                final PolicyEntity existingPolicy = PolicyEntity.fromDocument(existingDocument);
                existingPolicy.setPolicy(managedPolicy.getPolicy());
                existingPolicy.setDescription(managedPolicy.getDescription());
                existingPolicy.setLastUpdatedTimestamp(new Date());
                existingPolicy.incrementRevision();

                collection.replaceOne(Filters.eq("_id", existingPolicy.getId()), existingPolicy.toDocument());
                policyVersionDataService.snapshot(existingPolicy);
                LOGGER.info("Updated managed policy: {}", managedPolicy.getName());

            } else {

                // Insert new managed policy
                final ObjectId managedId = collection.insertOne(managedPolicy.toDocument()).getInsertedId().asObjectId().getValue();
                managedPolicy.setId(managedId);
                policyVersionDataService.snapshot(managedPolicy);
                LOGGER.info("Inserted managed policy: {}", managedPolicy.getName());

            }

        }

        LOGGER.info("Managed policies loaded successfully.");

    }

}
