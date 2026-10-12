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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyKeyWarnings;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.RedactionCache;
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
import com.mongodb.client.model.Updates;
import com.mongodb.client.result.DeleteResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** MongoDB implementation of {@link PolicyDataService}. */
public class MongoPolicyDataService extends AbstractService<PolicyEntity> implements PolicyDataService {

    private static final Logger LOGGER = LogManager.getLogger(PolicyDataService.class);

    public static final String INVALID_POLICY_NAME_MESSAGE = "The policy name must only contain letters, numbers, dashes, and underscores.";
    public static final int MAX_LIMIT = 100;

    private final Gson gson;
    private final PolicyVersionDataService policyVersionDataService;

    // Evicted on every write, so an edit governs the next redaction.
    private final RedactionCache redactionCache;

    public MongoPolicyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final Gson gson, final PolicyVersionDataService policyVersionDataService, final RedactionCache redactionCache) {
        super(mongoClient, "policies", auditEventPublisher);
        revisionCounters = mongoClient.getDatabase("philter").getCollection("policy_revision_counters");
        this.gson = gson;
        this.policyVersionDataService = policyVersionDataService;
        this.redactionCache = redactionCache;

        // User policies are listed/looked up by (user_id, name); managed policies by (managed, name).
        ensureIndex(Indexes.ascending("user_id", "name"), new com.mongodb.client.model.IndexOptions().unique(true));
        ensureIndex(Indexes.ascending("managed", "name"));
        // Listing a user's policies also matches shared policies via an OR branch on shared=true, so
        // index shared so that branch uses an index instead of scanning the collection.
        ensureIndex(Indexes.ascending("shared", "name"));
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
        final String expectedDescription = policyEntity.getDescription();
        final String expectedNotes = policyEntity.getNotes();

        // Metadata-only saves retain the current content revision.
        final boolean contentChanged = !Objects.equals(
                PolicyVersionDataService.contentHash(policyEntity.getPolicy()),
                PolicyVersionDataService.contentHash(policyJson));

        if (contentChanged) {
            policyEntity.setRevision(reserveRevision(policyEntity, expectedRevision + 1));
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

        PolicyKeyWarnings.warnAboutLiteralKeys(policyEntity.getName(), policyJson);

        policyEntity.setAuthorId(authorId);
        policyVersionDataService.snapshot(policyEntity);
        if (!replaceIfUnchanged(policyEntity, expectedRevision, expectedDescription, expectedNotes)) {
            return WriteOutcome.refused(new ServiceResponse("Policy changed concurrently. Reload and retry.", false, 409, REASON_POLICY_CHANGED));
        }

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
            } catch (final com.mongodb.MongoWriteException race) {
                // A concurrent create took the name after the check above; the unique index refused this one.
                if (race.getError().getCode() != 11000) throw race;
                return WriteOutcome.refused(new ServiceResponse("A policy with this name already exists.", false, 409, REASON_POLICY_EXISTS));
            }

            policyEntity.setId(policyId);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_CREATED, policyId, source);

            return new WriteOutcome(new ServiceResponse("The policy was created.", true, policyId, 200), policyEntity);

        }

    }

    @Override
    public boolean containsValidChoice(final String value, final List<String> allowedValues) {

        if(value == null) {
            return false;
        }

        return allowedValues.contains(value.toLowerCase());

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
            gson.fromJson(policyJson, Policy.class);

            LOGGER.info("Policy validation successful.");
            return PolicyValidation.valid("Policy is valid");

        } catch (final Exception ex) {

            // The policy json is not valid and could not be deserialized.
            LOGGER.warn("Policy validation failed: Policy contains syntax errors.");
            return PolicyValidation.invalid("Policy contains syntax errors.");

        }

    }

    @Override
    public List<PolicyEntity> findManagedPolicies() {

        final FindIterable<Document> documents = collection.find(Filters.eq("managed", true)).sort(Sorts.ascending("name"));

        final List<PolicyEntity> policies = new ArrayList<>();

        for(final Document document : documents) {

            final PolicyEntity policyEntity = PolicyDocuments.fromDocument(document);
            policies.add(policyEntity);

        }

        return policies;

    }

    @Override
    public List<PolicyEntity> findManagedPolicies(final int offset, final int limit) {

        final FindIterable<Document> documents = collection.find(Filters.eq("managed", true))
                .sort(Sorts.ascending("name")).skip(offset).limit(limit);

        final List<PolicyEntity> policies = new ArrayList<>();

        for (final Document document : documents) {
            policies.add(PolicyDocuments.fromDocument(document));
        }

        return policies;

    }

    @Override
    public Listings.Page<PolicyEntity> listOwn(final String userId, final String q, final Listings.Sort sort,
                                               final int offset, final int limit) {
        return MongoListings.page(collection, MongoListings.all(Filters.or(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("shared", true)),
                Filters.ne("managed", true), MongoListings.contains("name", q)), sort, offset, limit, PolicyDocuments::fromDocument);
    }

    @Override
    public Listings.Page<PolicyEntity> listAcrossUsers(final String q, final Listings.Sort sort, final int offset,
                                                       final int limit) {
        return MongoListings.page(collection, MongoListings.all(acrossUsers(false), MongoListings.contains("name", q)), sort, offset,
                limit, PolicyDocuments::fromDocument);
    }

    @Override
    public Listings.Page<PolicyEntity> listManaged(final String q, final Listings.Sort sort, final int offset,
                                                   final int limit) {
        return MongoListings.page(collection, MongoListings.all(Filters.eq("managed", true), MongoListings.contains("name", q)), sort,
                offset, limit, PolicyDocuments::fromDocument);
    }

    @Override
    public int countManagedPolicies() {
        return (int) collection.countDocuments(Filters.eq("managed", true));
    }

    @Override
    public List<PolicyEntity> find(final String userId, final String searchTerm) {

        final Pattern pattern = Pattern.compile(".*" + Pattern.quote(searchTerm) + ".*", Pattern.CASE_INSENSITIVE);

        final Bson query = Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
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

            final PolicyEntity policyEntity = PolicyDocuments.fromDocument(document);
            policies.add(policyEntity);

        }

        return policies;

    }

    @Override
    public List<PolicyEntity> findAllAcrossUsers() {

        final List<PolicyEntity> policies = new ArrayList<>();

        for (final Document document : collection.find()) {
            policies.add(PolicyDocuments.fromDocument(document));
        }

        return policies;

    }

    @Override
    public List<PolicyEntity> findAllAcrossUsers(final int offset, final int limit) {
        return findAllAcrossUsers(offset, limit, true);
    }

    @Override
    public List<PolicyEntity> findAllAcrossUsers(final int offset, final int limit, final boolean includeManaged) {

        final List<PolicyEntity> policies = new ArrayList<>();

        for (final Document document : collection.find(acrossUsers(includeManaged))
                .sort(Sorts.ascending("name", "_id")).skip(offset).limit(limit)) {
            policies.add(PolicyDocuments.fromDocument(document));
        }

        return policies;

    }

    @Override
    public int countAllAcrossUsers() {
        return countAllAcrossUsers(true);
    }

    @Override
    public int countAllAcrossUsers(final boolean includeManaged) {
        return (int) collection.countDocuments(acrossUsers(includeManaged));
    }

    private static Bson acrossUsers(final boolean includeManaged) {
        return includeManaged ? new Document() : Filters.ne("managed", true);
    }

    @Override
    public List<PolicyEntity> findAll(final String userId, final int offset, final int limit, final boolean includeManagedPolicies) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        final Bson query;

        if (userId != null) {
            query = Filters.or(
                    Filters.eq("user_id", MongoIds.objectId(userId)),
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

            final PolicyEntity policyEntity = PolicyDocuments.fromDocument(document);
            policies.add(policyEntity);

        }

        // Optionally include the managed policies.
        if(includeManagedPolicies) {

            // Now add the managed policies.
            policies.addAll(findManagedPolicies());

        }

        return policies;

    }

    @Override
    public List<PolicyEntity> findAll(final String userId, final int offset, final int limit, final boolean includeManagedPolicies, final String sortField, final boolean ascending) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        // Build the sort criteria
        final Bson sortCriteria = ascending ? Sorts.ascending(sortField) : Sorts.descending(sortField);

        final Bson query;

        if (userId != null) {
            query = Filters.or(
                    Filters.eq("user_id", MongoIds.objectId(userId)),
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

            final PolicyEntity policyEntity = PolicyDocuments.fromDocument(document);
            policies.add(policyEntity);

        }

        // Optionally include the managed policies.
        if(includeManagedPolicies) {

            // Now add the managed policies.
            policies.addAll(findManagedPolicies());

        }

        return policies;

    }

    @Override
    public int count(final String userId) {

        final Bson query;

        if (userId != null) {
            query = Filters.and(
                    Filters.eq("managed", false),
                    Filters.eq("user_id", MongoIds.objectId(userId))
            );
        } else {
            query = Filters.eq("managed", false);
        }

        final long count = collection.countDocuments(query);

        return (int) count;

    }

    @Override
    public boolean isPolicyNameUnique(final String name, final String userId) {

        return findOne(name, userId) == null;

    }

    @Override
    public PolicyEntity findOneById(final String id, final String userId) {

        final Bson query = Filters.and(
                Filters.eq("_id", MongoIds.objectId(id)),
                Filters.or(
                        Filters.eq("user_id", MongoIds.objectId(userId)),
                        Filters.eq("shared", true)
                )
        );

        final Document document = collection.find(query).first();

        if(document != null) {

            return PolicyDocuments.fromDocument(document);

        } else {

            return null;

        }

    }

    @Override
    public PolicyEntity findOneOrManaged(final String name, final String userId) {
        if (PolicyDataService.isManagedName(name)) {
            final Document document = collection.find(Filters.and(Filters.eq("name", name), Filters.eq("managed", true))).first();
            return document == null ? null : PolicyDocuments.fromDocument(document);
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

        final List<Bson> updates = new ArrayList<>();
        final List<String> changed = new ArrayList<>();
        if (description != null) {
            updates.add(Updates.set("description", description));
            changed.add("description");
        }
        if (notes != null) {
            updates.add(Updates.set("notes", notes));
            changed.add("notes");
        }
        if (updates.isEmpty()) {
            return new WriteOutcome(ServiceResponse.success(), policyEntity);
        }
        final Date now = new Date();
        updates.add(Updates.set("last_updated_timestamp", now));

        // Lands only if the policy and its details are as they were read, so a write between the read and
        // this one is not overwritten.
        final boolean written = collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(policyEntity.getId())),
                        Filters.eq("user_id", MongoIds.objectId(userId)), unchanged(policyEntity.getRevision(), policyEntity.getDescription(),
                                policyEntity.getNotes())),
                Updates.combine(updates)).getMatchedCount() == 1;
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
        policyEntity.setLastUpdatedTimestamp(now);
        return new WriteOutcome(ServiceResponse.success(), policyEntity);

    }

    @Override
    public PolicyEntity findOne(final String name, final String userId) {

        final Bson query = Filters.and(
                Filters.eq("name", name),
                Filters.eq("user_id", MongoIds.objectId(userId))
        );

        final Document document = collection.find(query).first();

        if(document != null) {

            return PolicyDocuments.fromDocument(document);

        } else {

            return null;

        }

    }

    @Override
    public ServiceResponse duplicatePolicy(final String requestId, final String userId, final String name, final String newName, String source) {

        // Make sure the policy name is not empty.
        if (newName == null || newName.isEmpty()) {
            return new ServiceResponse("Policy name cannot be empty.", false, 400);
        }

        // Make sure the policy name does not start with `managed_`
        if (newName.startsWith(MANAGED_NAME_PREFIX)) {
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

        // Make sure the policy name is not one the API's paths use for something else.
        if (RESERVED_POLICY_NAMES.contains(newName)) {
            return new ServiceResponse("The policy name " + newName + " is reserved.", false, 400);
        }

        // Make sure the new name is unique.
        if(!isPolicyNameUnique(newName, userId)) {
            return new ServiceResponse("A policy with this name already exists.", false, 409, REASON_POLICY_EXISTS);
        }

        final PolicyEntity policyEntity = findOne(name, userId);

        // The source policy must exist.
        if (policyEntity == null) {
            return new ServiceResponse("Policy does not exist.", false, 404);
        }

        policyEntity.setName(newName);
        policyEntity.setId(null);
        policyEntity.setUserId(userId);

        final String objectId = save(policyEntity);

        policyEntity.setId(objectId);

        redactionCache.evictPolicy(userId, newName);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_CREATED, objectId, source);

        return new ServiceResponse("The policy was duplicated.", true, objectId, 200);

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

        final int expectedRevision = live.getRevision();
        live.setPolicy(targetVersion.getPolicy());
        live.setRevision(reserveRevision(live, expectedRevision + 1));
        live.setLastUpdatedTimestamp(new Date());

        final int newRevision = live.getRevision();
        live.setAuthorId(principalId);
        policyVersionDataService.snapshot(live);
        if (!replaceIfUnchanged(live, expectedRevision, live.getDescription(), live.getNotes())) {
            return WriteOutcome.refused(new ServiceResponse("Policy changed concurrently. Reload and retry.", false, 409, REASON_POLICY_CHANGED));
        }

        redactionCache.evictPolicy(userId, policyName);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_ROLLED_BACK, principalId, live.getId(),
                clientIpAddress, "policy: " + policyName + ", rolled back to revision: " + targetRevision
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

        // There should never be a policy that is "managed=true" and has a user_id != null but this is just a safeguard.
        final Document query = new Document("name", policyName).append("managed", false).append("user_id", MongoIds.objectId(userId));
        final DeleteResult deleteResult = collection.deleteOne(query);

        if(deleteResult.getDeletedCount() == 1) {

            redactionCache.evictPolicy(userId, policyName);
            policyVersionDataService.recordDeletion(userId, policyName, policyEntity.getRevision(), principalId, new Date());

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.POLICY_DELETED, principalId, policyEntity.getId(),
                    clientIpAddress, "policy: " + policyName + ", source: " + source.getSource());

            return new ServiceResponse("Policy deleted.", true, 200);

        } else {

            return new ServiceResponse("Policy does not exist.", false, 404);

        }

    }

    @Override
    public Listings.Page<DeletedPolicy> findDeleted(final String userId, final String q, final boolean descending,
                                                    final int offset, final int limit) {

        final java.util.Set<String> live = new java.util.HashSet<>();
        collection.distinct("name", Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.ne("managed", true)), String.class)
                .into(live);

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
    public long deleteAll() {

        // There should never be a policy that is "managed=true" and has a user_id != null, but this is just a safeguard.
        final Document query = new Document("managed", false);

        final DeleteResult deleteResult = collection.deleteMany(query);

        return deleteResult.getDeletedCount();

    }

    @Override
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
                final PolicyEntity existingPolicy = PolicyDocuments.fromDocument(existingDocument);
                final int expectedRevision = existingPolicy.getRevision();
                existingPolicy.setPolicy(managedPolicy.getPolicy());
                existingPolicy.setDescription(managedPolicy.getDescription());
                existingPolicy.setLastUpdatedTimestamp(new Date());
                existingPolicy.setRevision(reserveRevision(existingPolicy, expectedRevision + 1));

                policyVersionDataService.snapshot(existingPolicy);
                if (!replaceRevision(existingPolicy, expectedRevision)) {
                    throw new IllegalStateException("Managed policy changed concurrently; restart initialization.");
                }
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

    private final com.mongodb.client.MongoCollection<Document> revisionCounters;

    /** Sequence survives deletion/name reuse. Gaps are expected for failed/conflicting attempts. */
    private int reserveRevision(PolicyEntity policy, int minimum) {
        final Document identity = new Document("user_id", MongoIds.objectId(policy.getUserId())).append("name", policy.getName());
        // Reserve above the observed head, even when a competing candidate consumed a revision.
        try {
            revisionCounters.updateOne(Filters.eq("_id", identity),
                    new Document("$max", new Document("next", minimum)),
                    new com.mongodb.client.model.UpdateOptions().upsert(true));
        } catch (com.mongodb.MongoWriteException race) {
            if (race.getError().getCode() != 11000) throw race;
            revisionCounters.updateOne(Filters.eq("_id", identity), new Document("$max", new Document("next", minimum)));
        }
        final Document result = revisionCounters.findOneAndUpdate(Filters.eq("_id", identity),
                new Document("$inc", new Document("next", 1)),
                new com.mongodb.client.model.FindOneAndUpdateOptions().upsert(true)
                        .returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
        return result.getInteger("next") - 1;
    }

    @Override
    public String save(PolicyEntity policy) {
        policy.setRevision(reserveRevision(policy, 0));
        policyVersionDataService.snapshot(policy);
        return super.save(policy);
    }

    /** Replaces the policy only if its revision, description, and notes are still the ones given. */
    private boolean replaceIfUnchanged(final PolicyEntity policy, final int expectedRevision,
                                       final String expectedDescription, final String expectedNotes) {
        return collection.replaceOne(Filters.and(Filters.eq("_id", MongoIds.objectId(policy.getId())),
                unchanged(expectedRevision, expectedDescription, expectedNotes)), PolicyDocuments.toDocument(policy)).getMatchedCount() == 1;
    }

    /** Matches a policy still at this revision with this description and notes; null matches null or absent. */
    private static Bson unchanged(final int revision, final String description, final String notes) {
        return Filters.and(Filters.eq("revision", revision), Filters.eq("description", description),
                Filters.eq("notes", notes));
    }

    private boolean replaceRevision(PolicyEntity policy, int expectedRevision) {
        return collection.replaceOne(Filters.and(Filters.eq("_id", MongoIds.objectId(policy.getId())),
                Filters.eq("revision", expectedRevision)), PolicyDocuments.toDocument(policy)).getMatchedCount() == 1;
    }

    @Override
    public void update(PolicyEntity policy) {
        throw new UnsupportedOperationException("Use a revision-checked policy update.");
    }
    @Override
    protected Document toDocument(final PolicyEntity entity) {
        return PolicyDocuments.toDocument(entity);
    }

}
