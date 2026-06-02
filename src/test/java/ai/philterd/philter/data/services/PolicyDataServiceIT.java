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

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import ai.philterd.philter.services.policies.SimplifiedStrategy;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link PolicyDataService} against a real (in-memory) MongoDB. These cover
 * the create/update/find/findAll(paging + sorting)/count/findOne/findOneById/duplicate/delete and
 * name-uniqueness operations with real round-trips — user scoping, persistence, mutation, and
 * deletion behavior the mock-based unit tests can only approximate.
 */
class PolicyDataServiceIT extends AbstractMongoIT {

    private PolicyDataService service;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUpService() {
        service = new PolicyDataService(mongoClient, mock(AuditEventPublisher.class), gson);
    }

    /** Builds a valid policy JSON (a single supported SSN filter), matching the unit-test fixture. */
    private String validPolicyJson() {
        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(FilterType.SSN, List.of(new SimplifiedStrategy("REDACT"))));
        return gson.toJson(policy);
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
        assertEquals(originalRevision + 1, updated.getRevision());
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

        final ServiceResponse response = service.deleteByName("req", "delete-me", user, Source.API);
        assertTrue(response.isSuccessful());
        assertNull(service.findOne("delete-me", user));
    }

    @Test
    void deleteByNameReturns404ForUnknownPolicy() {
        final ServiceResponse response = service.deleteByName("req", "nope", new ObjectId(), Source.API);
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

}
