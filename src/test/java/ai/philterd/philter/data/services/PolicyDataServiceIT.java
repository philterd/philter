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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import java.util.ArrayList;

/**
 * Integration tests for {@link PolicyDataService} against a real (in-memory) MongoDB. These cover
 * the create/update/find/findAll(paging + sorting)/count/findOne/findOneById/duplicate/delete and
 * name-uniqueness operations with real round-trips — user scoping, persistence, mutation, and
 * deletion behavior the mock-based unit tests can only approximate.
 */
class PolicyDataServiceIT extends AbstractMongoIT {

    private PolicyDataService service;
    private PolicyVersionDataService versionService;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUpService() {
        versionService = new PolicyVersionDataService(mongoClient, mock(AuditEventPublisher.class));
        service = new PolicyDataService(mongoClient, mock(AuditEventPublisher.class), gson,
                versionService, new ai.philterd.philter.services.cache.RedactionCache());
    }

    /** Builds a valid native policy JSON (a single SSN filter), matching the unit-test fixture. */
    private String validPolicyJson() {
        return "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    }

    private ServiceResponse create(final ObjectId userId, final String name) {
        return service.create("req", userId, validPolicyJson(), "desc", "notes", name, "source");
    }

    @Test
    void createPersistsAndFindOneReadsItBack() {
        final ObjectId user = new ObjectId();
        final ServiceResponse response = create(user, "policy1");
        assertTrue(response.isSuccessful());

        final PolicyEntity found = service.findOne("policy1", user);
        assertNotNull(found);
        assertEquals("policy1", found.getName());
        assertEquals(user, found.getUserId());
        assertEquals("desc", found.getDescription());
        assertEquals("notes", found.getNotes());
        assertNotNull(found.getPolicy());
    }

    @Test
    void createRejectsInvalidPolicyJson() {
        final ServiceResponse response = service.create("req", new ObjectId(),
                "{ not valid json", "desc", "notes", "bad", "source");
        assertFalse(response.isSuccessful());
        assertEquals(400, response.getStatusCode());
        // Nothing was persisted.
        assertNull(service.findOne("bad", new ObjectId()));
    }

    @Test
    void createRejectsDuplicateNameForSameUser() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "dup").isSuccessful());

        final ServiceResponse response = create(user, "dup");
        assertFalse(response.isSuccessful());
        assertEquals(409, response.getStatusCode());
        assertEquals(PolicyDataService.REASON_POLICY_EXISTS, response.getDetails());
    }

    @Test
    void policiesAreScopedByUser() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        assertTrue(create(userA, "shared-name").isSuccessful());

        // findOne is owner-scoped: another user cannot see it.
        assertNull(service.findOne("shared-name", userB));

        // The same name is unique per user, so userB may create it independently.
        assertTrue(create(userB, "shared-name").isSuccessful());
        assertNotNull(service.findOne("shared-name", userA));
        assertNotNull(service.findOne("shared-name", userB));
    }

    @Test
    void isPolicyNameUniqueReflectsPersistedState() {
        final ObjectId user = new ObjectId();
        assertTrue(service.isPolicyNameUnique("fresh", user));

        assertTrue(create(user, "fresh").isSuccessful());
        assertFalse(service.isPolicyNameUnique("fresh", user));

        // Uniqueness is per-user.
        assertTrue(service.isPolicyNameUnique("fresh", new ObjectId()));
    }

    @Test
    void findOneByIdIsScopedToOwner() {
        final ObjectId user = new ObjectId();
        final ServiceResponse response = create(user, "byid");
        final ObjectId policyId = response.getObjectId();

        final PolicyEntity found = service.findOneById(policyId, user);
        assertNotNull(found);
        assertEquals("byid", found.getName());

        // A different user cannot look it up by id.
        assertNull(service.findOneById(policyId, new ObjectId()));
    }

    @Test
    void updateMutatesPersistedPolicy() {
        final ObjectId user = new ObjectId();
        final ServiceResponse created = create(user, "to-update");
        final ObjectId policyId = created.getObjectId();

        final int originalRevision = service.findOneById(policyId, user).getRevision();

        final ServiceResponse response = service.update("req", user, policyId,
                validPolicyJson(), "new description", "new notes", "source");
        assertTrue(response.isSuccessful());

        final PolicyEntity updated = service.findOneById(policyId, user);
        assertEquals("new description", updated.getDescription());
        assertEquals("new notes", updated.getNotes());

        // Description and notes are not policy content, so this is not a new revision.
        assertEquals(originalRevision, updated.getRevision());
    }

    @Test
    @DisplayName("A content change advances the revision")
    void aContentChangeAdvancesTheRevision() {
        final ObjectId user = new ObjectId();
        final ObjectId policyId = create(user, "to-edit").getObjectId();

        final int originalRevision = service.findOneById(policyId, user).getRevision();

        assertTrue(service.update("req", user, policyId,
                validPolicyJson().replace("REDACT", "MASK"), null, null, "source").isSuccessful());

        assertEquals(originalRevision + 1, service.findOneById(policyId, user).getRevision());
    }

    @Test
    void updateReturns404WhenPolicyMissing() {
        final ServiceResponse response = service.update("req", new ObjectId(), new ObjectId(),
                validPolicyJson(), "d", "n", "source");
        assertFalse(response.isSuccessful());
        assertEquals(404, response.getStatusCode());
    }

    @Test
    void duplicateProducesANewDocument() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "original").isSuccessful());

        final ServiceResponse response = service.duplicatePolicy("req", user, "original", "copy", "source");
        assertTrue(response.isSuccessful());

        // Both the source and the copy exist independently.
        final PolicyEntity original = service.findOne("original", user);
        final PolicyEntity copy = service.findOne("copy", user);
        assertNotNull(original);
        assertNotNull(copy);
        assertFalse(original.getId().equals(copy.getId()), "Duplicate must be a new document");
        assertEquals(original.getPolicy(), copy.getPolicy());
        assertEquals(2, service.count(user));
    }

    @Test
    void duplicateRejectsExistingTargetName() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "src").isSuccessful());
        assertTrue(create(user, "taken").isSuccessful());

        final ServiceResponse response = service.duplicatePolicy("req", user, "src", "taken", "source");
        assertFalse(response.isSuccessful());
        assertEquals(409, response.getStatusCode());
    }

    @Test
    void deleteByNameRemovesPolicy() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "delete-me").isSuccessful());

        final ServiceResponse response = service.deleteByName("req", "delete-me", user, Source.API, user, "10.0.0.1");
        assertTrue(response.isSuccessful());
        assertNull(service.findOne("delete-me", user));
    }

    @Test
    @DisplayName("The default policy is not deleted, and the refusal says why")
    void deleteByNameKeepsTheDefaultPolicy() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "default").isSuccessful());

        final ServiceResponse response = service.deleteByName("req", "default", user, Source.API, user, "10.0.0.1");
        assertEquals(409, response.getStatusCode());
        assertEquals("Cannot delete the default policy.", response.getMessage());
        assertEquals(PolicyDataService.REASON_POLICY_DEFAULT, response.getDetails());
        assertNotNull(service.findOne("default", user), "the default policy is kept");
    }

    @Test
    void deleteByNameReturns404ForUnknownPolicy() {
        final ServiceResponse response = service.deleteByName("req", "nope", new ObjectId(), Source.API, new ObjectId(), "10.0.0.1");
        assertFalse(response.isSuccessful());
        assertEquals(404, response.getStatusCode());
    }

    @Test
    void deleteAllRemovesUnmanagedPolicies() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        assertTrue(create(userA, "p1").isSuccessful());
        assertTrue(create(userA, "p2").isSuccessful());
        assertTrue(create(userB, "p3").isSuccessful());

        assertEquals(3L, service.deleteAll());
        assertEquals(0, service.count(userA));
        assertEquals(0, service.count(userB));
    }

    @Test
    void countExcludesOtherUsers() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        assertTrue(create(userA, "a1").isSuccessful());
        assertTrue(create(userA, "a2").isSuccessful());
        assertTrue(create(userB, "b1").isSuccessful());

        assertEquals(2, service.count(userA));
        assertEquals(1, service.count(userB));
    }

    @Test
    void findAllSupportsPaging() {
        final ObjectId user = new ObjectId();
        for (int i = 0; i < 5; i++) {
            assertTrue(create(user, "policy-" + i).isSuccessful());
        }

        assertEquals(2, service.findAll(user, 0, 2, false).size());
        assertEquals(2, service.findAll(user, 2, 2, false).size());
        assertEquals(1, service.findAll(user, 4, 2, false).size());
        assertEquals(5, service.findAll(user, 0, 100, false).size());

        // findAll is scoped: another user sees nothing.
        assertEquals(0, service.findAll(new ObjectId(), 0, 100, false).size());
    }

    @Test
    void findAllSupportsSorting() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "bbb").isSuccessful());
        assertTrue(create(user, "aaa").isSuccessful());
        assertTrue(create(user, "ccc").isSuccessful());

        final List<PolicyEntity> ascending = service.findAll(user, 0, 100, false, "name", true);
        assertEquals(List.of("aaa", "bbb", "ccc"),
                ascending.stream().map(PolicyEntity::getName).toList());

        final List<PolicyEntity> descending = service.findAll(user, 0, 100, false, "name", false);
        assertEquals(List.of("ccc", "bbb", "aaa"),
                descending.stream().map(PolicyEntity::getName).toList());
    }

    @Test
    void findFiltersBySearchTermAcrossNameDescriptionNotes() {
        final ObjectId user = new ObjectId();
        assertTrue(service.create("req", user, validPolicyJson(), "the alpha policy", "notes", "first", "source").isSuccessful());
        assertTrue(service.create("req", user, validPolicyJson(), "desc", "notes", "second", "source").isSuccessful());

        // Matches the name of the second policy.
        final List<PolicyEntity> byName = service.find(user, "second");
        assertEquals(1, byName.size());
        assertEquals("second", byName.get(0).getName());

        // Matches the description of the first policy (case-insensitive).
        final List<PolicyEntity> byDescription = service.find(user, "ALPHA");
        assertEquals(1, byDescription.size());
        assertEquals("first", byDescription.get(0).getName());

        // Matches notes shared by both policies.
        assertEquals(2, service.find(user, "notes").size());

        // No match.
        assertTrue(service.find(user, "zzz").isEmpty());

        // Search is user-scoped.
        assertTrue(service.find(new ObjectId(), "notes").isEmpty());
    }


    @Test
    @DisplayName("An unchanged save does not advance the revision")
    void anUnchangedSaveDoesNotAdvanceTheRevision() {
        final ObjectId userId = new ObjectId();
        service.create("req", userId, validPolicyJson(), null, null, "p", "test");

        final int created = service.findOne("p", userId).getRevision();

        for (int i = 0; i < 3; i++) {
            assertTrue(service.update("req", userId, service.findOne("p", userId).getId(),
                    validPolicyJson(), null, null, "test").isSuccessful());
        }

        assertEquals(created, service.findOne("p", userId).getRevision(),
                "saving identical content is not a new version");
    }

    @Test
    @DisplayName("Every revision the policy reaches has a snapshot behind it")
    void everyRevisionHasASnapshot() {
        final ObjectId userId = new ObjectId();
        service.create("req", userId, validPolicyJson(), null, null, "p", "test");

        // A real edit, then a no-op save. The no-op must be what leaves the policy on its final
        // revision: ending on a real edit would pass either way, since that revision is snapshotted.
        service.update("req", userId, service.findOne("p", userId).getId(),
                validPolicyJson().replace("REDACT", "MASK"), null, null, "test");
        service.update("req", userId, service.findOne("p", userId).getId(),
                validPolicyJson().replace("REDACT", "MASK"), null, null, "test");

        final int revision = service.findOne("p", userId).getRevision();

        // The symptom of D-12: a policy that could not be rolled back to the revision it was on.
        assertTrue(service.rollback("req", "p", userId, revision, userId, "10.0.0.1").isSuccessful(),
                "the policy must be able to roll back to its own revision " + revision);
    }


    @Test
    @DisplayName("A rollback's new revision has a snapshot behind it")
    void aRollbackLeavesItsRevisionResolvable() {
        final ObjectId userId = new ObjectId();
        service.create("req", userId, validPolicyJson(), null, null, "p", "test");
        service.update("req", userId, service.findOne("p", userId).getId(),
                validPolicyJson().replace("REDACT", "MASK"), null, null, "test");

        // Rollback restores content that is already snapshotted at an earlier revision.
        assertTrue(service.rollback("req", "p", userId, 0, userId, "10.0.0.1").isSuccessful());

        final int afterRollback = service.findOne("p", userId).getRevision();

        assertTrue(service.rollback("req", "p", userId, afterRollback, userId, "10.0.0.1").isSuccessful(),
                "the revision a rollback lands on must itself be resolvable");
    }

    @Test
    @DisplayName("A rollback that read the policy before another change is refused with policy_changed")
    void aRollbackOverAConcurrentChangeIsRefused() {
        final ObjectId userId = new ObjectId();
        service.create("req", userId, validPolicyJson(), null, null, "p", "test");
        final PolicyEntity stale = service.findOne("p", userId);

        // Another change lands after the rollback read the policy.
        service.update("req", userId, stale.getId(), validPolicyJson().replace("REDACT", "MASK"), null, null, "test");
        final PolicyDataService racing = spy(service);
        doReturn(stale).when(racing).findOne("p", userId);

        final ServiceResponse response = racing.rollback("req", "p", userId, 0, userId, "10.0.0.1");
        assertEquals(409, response.getStatusCode());
        assertEquals(PolicyDataService.REASON_POLICY_CHANGED, response.getDetails());
        assertTrue(service.findOne("p", userId).getPolicy().contains("MASK"), "the other change is kept");
    }

    @Test
    @DisplayName("Editing back to earlier content still snapshots the new revision")
    void editingBackToEarlierContentIsSnapshotted() {
        final ObjectId userId = new ObjectId();
        final ObjectId policyId = service.create("req", userId, validPolicyJson(), null, null, "p", "test").getObjectId();

        service.update("req", userId, policyId, validPolicyJson().replace("REDACT", "MASK"), null, null, "test");
        service.update("req", userId, policyId, validPolicyJson(), null, null, "test");

        final int revision = service.findOne("p", userId).getRevision();

        assertTrue(service.rollback("req", "p", userId, revision, userId, "10.0.0.1").isSuccessful(),
                "A -> B -> A must leave revision " + revision + " resolvable");
    }

    @Test
    @DisplayName("Two users with identical policy content each keep their own snapshot")
    void identicalContentIsSnapshottedPerUser() {
        final ObjectId first = new ObjectId();
        final ObjectId second = new ObjectId();

        service.create("req", first, validPolicyJson(), null, null, "shared-name", "test");
        service.create("req", second, validPolicyJson(), null, null, "shared-name", "test");

        assertNotNull(versionService.findByNameAndRevision("shared-name", first, 0));
        assertNotNull(versionService.findByNameAndRevision("shared-name", second, 0),
                "the second user's snapshot must not be swallowed by the first's identical content");
    }


    @Test
    @DisplayName("Saving a policy that carries its own key warns, and saving one that does not is quiet")
    void savingAPolicyWithALiteralKeyWarns() {

        final ObjectId userId = new ObjectId();
        final String withKey = "{\"crypto\":{\"key\":\"0011223344556677\"},"
                + "\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

        final String warned = capturingPolicyLogs(() ->
                service.create("req", userId, withKey, "d", null, "with-key", "test"));

        assertTrue(warned.contains("with-key"), "the warning must name the policy: " + warned);
        assertTrue(warned.contains("env:"), "the warning must give the way out: " + warned);

        final String quiet = capturingPolicyLogs(() ->
                service.create("req", userId, validPolicyJson(), "d", null, "no-key", "test"));

        assertFalse(quiet.contains("not encrypted at rest"),
                "a policy holding no key must not be warned about: " + quiet);

        // The warning is advice, not a refusal: the policy is still stored.
        assertNotNull(service.findOne("with-key", userId));

    }

    /** Runs the action with PolicyDataService's logger captured, and returns what it logged. */
    // ----- Entity tags and preconditions -----

    private static final String OTHER_POLICY_JSON =
            "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Test
    @DisplayName("The entity tag changes with the details, though the revision does not")
    void eTagFollowsTheDetails() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "tagged").isSuccessful());
        final PolicyEntity before = service.findOne("tagged", user);

        assertTrue(service.updateDetails("req", user, "tagged", null, "new notes", "source").isSuccessful());
        final PolicyEntity after = service.findOne("tagged", user);

        assertEquals(before.getRevision(), after.getRevision());
        assertFalse(PolicyDataService.eTag(before).equals(PolicyDataService.eTag(after)));
        assertTrue(PolicyDataService.ETAG_PATTERN.matcher(PolicyDataService.eTag(after)).matches());
        // Null and empty are different details, so they are different tags.
        final PolicyEntity cleared = service.updateDetails("req", user, "tagged", null, "", "source", null).policy();
        assertFalse(PolicyDataService.eTag(after).equals(PolicyDataService.eTag(cleared)));
    }

    @Test
    @DisplayName("A replace with a stale entity tag is refused and writes nothing; with the current one it lands")
    void replaceHonorsTheEntityTag() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "guarded").isSuccessful());
        final PolicyEntity read = service.findOne("guarded", user);
        final String staleTag = PolicyDataService.eTag(read);

        // Someone else edits only the notes, which leaves the revision as it was.
        assertTrue(service.updateDetails("req", user, "guarded", null, "theirs", "source").isSuccessful());

        final PolicyDataService.WriteOutcome refused = service.replace("req", user, read.getId(), OTHER_POLICY_JSON,
                "mine", null, "source", user, staleTag);
        assertEquals(409, refused.response().getStatusCode());
        assertEquals(PolicyDataService.REASON_POLICY_CHANGED, refused.response().getDetails());
        assertNull(refused.policy());
        final PolicyEntity unchanged = service.findOne("guarded", user);
        assertEquals(read.getPolicy(), unchanged.getPolicy());
        assertEquals(read.getRevision(), unchanged.getRevision());
        assertEquals("desc", unchanged.getDescription());
        assertEquals("theirs", unchanged.getNotes());

        final PolicyDataService.WriteOutcome written = service.replace("req", user, read.getId(), OTHER_POLICY_JSON,
                "mine", null, "source", user, PolicyDataService.eTag(unchanged));
        assertTrue(written.response().isSuccessful(), written.response().getMessage());
        final PolicyEntity stored = service.findOne("guarded", user);
        assertEquals(OTHER_POLICY_JSON, stored.getPolicy());
        assertEquals(read.getRevision() + 1, stored.getRevision());
        assertEquals("mine", stored.getDescription());
        assertEquals("theirs", stored.getNotes(), "notes left out are kept");
        assertEquals(PolicyDataService.eTag(stored), PolicyDataService.eTag(written.policy()),
                "the outcome carries the policy as written");
    }

    @Test
    @DisplayName("Without an entity tag, replace and details writes are not checked against an earlier read")
    void writesWithoutAnEntityTagAreUnconditional() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "open").isSuccessful());
        final PolicyEntity read = service.findOne("open", user);
        assertTrue(service.updateDetails("req", user, "open", "changed", null, "source").isSuccessful());

        assertTrue(service.replace("req", user, read.getId(), OTHER_POLICY_JSON, null, null, "source", user, null)
                .response().isSuccessful());
        assertTrue(service.updateDetails("req", user, "open", null, "x", "source", null).response().isSuccessful());
    }

    @Test
    @DisplayName("A replace sets the description and notes with the policy: empty clears, null keeps, too long is refused")
    void replaceWritesTheDetailsWithThePolicy() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "details").isSuccessful());
        final PolicyEntity read = service.findOne("details", user);

        assertTrue(service.replace("req", user, read.getId(), read.getPolicy(), "", null, "source", user, null)
                .response().isSuccessful());
        final PolicyEntity stored = service.findOne("details", user);
        assertEquals("", stored.getDescription());
        assertEquals("notes", stored.getNotes());
        assertEquals(read.getRevision(), stored.getRevision(), "the same policy with new details is not a new revision");

        final PolicyDataService.WriteOutcome tooLong = service.replace("req", user, read.getId(), OTHER_POLICY_JSON,
                "d".repeat(PolicyDataService.POLICY_DESCRIPTION_MAX_LENGTH + 1), null, "source", user, null);
        assertEquals(400, tooLong.response().getStatusCode());
        assertEquals(read.getPolicy(), service.findOne("details", user).getPolicy(), "neither the policy nor the details were written");
    }

    @Test
    @DisplayName("A change between the read and the write refuses the write even without an entity tag")
    void aConcurrentDetailsChangeRefusesTheWrite() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "raced").isSuccessful());
        final PolicyEntity read = service.findOne("raced", user);
        // Another writer changes the notes after this service read the policy.
        assertTrue(service.updateDetails("req", user, "raced", null, "theirs", "source").isSuccessful());

        final PolicyDataService racing = spy(service);
        doReturn(read).when(racing).findOne("raced", user);
        final PolicyDataService.WriteOutcome details = racing.updateDetails("req", user, "raced", "mine", null, "source", null);
        assertEquals(409, details.response().getStatusCode());
        assertEquals(PolicyDataService.REASON_POLICY_CHANGED, details.response().getDetails());

        final PolicyEntity readAgain = service.findOne("raced", user);
        readAgain.setNotes("notes");
        final PolicyDataService racingReplace = spy(service);
        doReturn(readAgain).when(racingReplace).findOneById(read.getId(), user);
        final PolicyDataService.WriteOutcome replaced = racingReplace.replace("req", user, read.getId(), OTHER_POLICY_JSON,
                null, null, "source", user, null);
        assertEquals(409, replaced.response().getStatusCode());

        final PolicyEntity stored = service.findOne("raced", user);
        assertEquals("desc", stored.getDescription());
        assertEquals("theirs", stored.getNotes());
        assertEquals(read.getPolicy(), stored.getPolicy());
    }

    @Test
    @DisplayName("Details and rollback writes honor the entity tag too")
    void detailsAndRollbackHonorTheEntityTag() {
        final ObjectId user = new ObjectId();
        assertTrue(create(user, "both").isSuccessful());
        final PolicyEntity first = service.findOne("both", user);
        assertTrue(service.replace("req", user, first.getId(), OTHER_POLICY_JSON, null, null, "source", user, null)
                .response().isSuccessful());
        final String staleTag = PolicyDataService.eTag(first);

        final PolicyDataService.WriteOutcome details = service.updateDetails("req", user, "both", "x", null, "source", staleTag);
        assertEquals(409, details.response().getStatusCode());
        assertEquals("desc", service.findOne("both", user).getDescription());

        final PolicyDataService.WriteOutcome rollback = service.rollback("req", "both", user, first.getRevision(), user,
                null, staleTag);
        assertEquals(409, rollback.response().getStatusCode());
        assertEquals(PolicyDataService.REASON_POLICY_CHANGED, rollback.response().getDetails());
        assertEquals(OTHER_POLICY_JSON, service.findOne("both", user).getPolicy());

        final PolicyEntity current = service.findOne("both", user);
        final PolicyDataService.WriteOutcome rolledBack = service.rollback("req", "both", user, first.getRevision(), user,
                null, PolicyDataService.eTag(current));
        assertTrue(rolledBack.response().isSuccessful(), rolledBack.response().getMessage());
        assertEquals(first.getPolicy(), service.findOne("both", user).getPolicy());
        assertEquals(PolicyDataService.eTag(service.findOne("both", user)), PolicyDataService.eTag(rolledBack.policy()));

        final PolicyEntity afterRollback = service.findOne("both", user);
        final PolicyDataService.WriteOutcome detailsWritten = service.updateDetails("req", user, "both", "y", null, "source",
                PolicyDataService.eTag(afterRollback));
        assertTrue(detailsWritten.response().isSuccessful());
        assertEquals("y", service.findOne("both", user).getDescription());
        assertEquals(PolicyDataService.eTag(service.findOne("both", user)), PolicyDataService.eTag(detailsWritten.policy()));
    }

    @Test
    @DisplayName("A create stores the description and notes with the policy and returns it as written")
    void createPolicyReturnsThePolicyAsWritten() {
        final ObjectId user = new ObjectId();
        final PolicyDataService.WriteOutcome created = service.createPolicy("req", user, validPolicyJson(), "d", "n",
                "fresh-with-details", "source", user);
        assertTrue(created.response().isSuccessful());
        final PolicyEntity stored = service.findOne("fresh-with-details", user);
        assertEquals("d", stored.getDescription());
        assertEquals("n", stored.getNotes());
        assertEquals(PolicyDataService.eTag(stored), PolicyDataService.eTag(created.policy()));
    }

    private String capturingPolicyLogs(final Runnable action) {

        final List<String> messages = new ArrayList<>();
        final org.apache.logging.log4j.core.appender.AbstractAppender appender =
                new org.apache.logging.log4j.core.appender.AbstractAppender("capture", null, null, true,
                        org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
                    @Override
                    public void append(final org.apache.logging.log4j.core.LogEvent event) {
                        messages.add(event.getMessage().getFormattedMessage());
                    }
                };
        appender.start();

        final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger)
                org.apache.logging.log4j.LogManager.getLogger(PolicyDataService.class);
        logger.addAppender(appender);

        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }

        return String.join("\n", messages);

    }

}
