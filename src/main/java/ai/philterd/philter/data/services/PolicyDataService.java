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
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.policies.ManagedPolicyLoader;
import ai.philterd.philter.services.policies.PolicyValidation;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import com.google.gson.Gson;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
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

    public PolicyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final Gson gson) {
        super(mongoClient, "policies", auditEventPublisher);
        this.gson = gson;
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

        // TODO: Don't overwrite the existing notes if none are passed in.
        // None might be passed in if not updating via the API or if using the Adv Policy Editor.
        if(policyNotes != null) {

            // Truncate the notes to 1000 characters, if needed.
            if(policyNotes.length() > POLICY_NOTES_MAX_LENGTH) {
                final String truncatedNotes = policyNotes.substring(0, POLICY_NOTES_MAX_LENGTH);
                policyEntity.setNotes(truncatedNotes);
            } else {
                policyEntity.setNotes(policyNotes);
            }

        }

        // TODO: Don't overwrite the existing description if none are passed in.
        // None might be passed in if not updating via the API or if using the Adv Policy Editor.
        if(policyDescription != null) {

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

            final SimplifiedPolicy simplifiedPolicy = gson.fromJson(policyJson, SimplifiedPolicy.class);

            if(!containsValidChoice(simplifiedPolicy.getDisambiguationScope(), SimplifiedPolicy.DISAMBIGUATION_SCOPES)) {
                LOGGER.warn("Policy validation failed: Invalid disambiguation scope.");
                return PolicyValidation.invalid("Disambiguation scope must be one of: " + String.join(", ", SimplifiedPolicy.DISAMBIGUATION_SCOPES));
            }

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
        policyEntity.setName(newName);
        policyEntity.setId(null);
        policyEntity.setUserId(userId);

        final ObjectId objectId = collection.insertOne(policyEntity.toDocument()).getInsertedId().asObjectId().getValue();

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_CREATED, objectId, source);

        return new ServiceResponse("The policy was duplicated.", true, objectId, 200);

    }

    public ServiceResponse deleteByName(final String requestId, final String policyName, final ObjectId userId, final String source) {

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

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_DELETED, null, null,"Policy Name: " + policyName, source);

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
                LOGGER.info("Updated managed policy: {}", managedPolicy.getName());

            } else {

                // Insert new managed policy
                collection.insertOne(managedPolicy.toDocument());
                LOGGER.info("Inserted managed policy: {}", managedPolicy.getName());

            }

        }

        LOGGER.info("Managed policies loaded successfully.");

    }

}
