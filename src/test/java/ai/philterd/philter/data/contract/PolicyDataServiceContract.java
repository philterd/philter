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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.Source;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link PolicyDataService}. */
public interface PolicyDataServiceContract extends StorageContract {

    String POLICY_A = "{\"identifiers\":{\"emailAddress\":{\"emailAddressFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    String POLICY_B = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    private PolicyEntity createPolicy(final String userId, final String name, final String json) {
        final PolicyDataService.WriteOutcome created = storage().policies()
                .createPolicy("req", userId, json, "description", "notes", name, "test", userId);
        assertTrue(created.response().isSuccessful(), created.response().getMessage());
        return created.policy();
    }

    @Test
    default void policyRevisionIsReadWithoutThePolicy() {
        final String owner = RecordIds.newId();
        assertNull(storage().policies().findRevision("claims", owner), "no policy, no revision");
        final PolicyEntity created = createPolicy(owner, "claims", POLICY_A);
        assertEquals(created.getRevision(), storage().policies().findRevision("claims", owner));
        final PolicyDataService.WriteOutcome written = storage().policies().replace("req", owner, created.getId(),
                POLICY_B, null, null, "test", owner, null);
        assertTrue(written.response().isSuccessful(), written.response().getMessage());
        assertEquals(written.policy().getRevision(), storage().policies().findRevision("claims", owner),
                "an edit moves the revision redaction checks its cached copy against");
        assertNotEquals(created.getRevision(), written.policy().getRevision());
        assertNull(storage().policies().findRevision("claims", RecordIds.newId()), "another user's policy is not found");
    }

    @Test
    default void policyAnotherUsersPolicyIsNeitherFoundNorListed() {
        final String owner = RecordIds.newId();
        final String other = RecordIds.newId();
        final PolicyEntity created = createPolicy(owner, "private", POLICY_A);
        assertNull(storage().policies().findOneById(created.getId(), other));
        assertNull(storage().policies().findOne("private", other));
        assertEquals(0, storage().policies().listOwn(other, null, new Listings.Sort("name", false), 0, 10).total());
        assertEquals(created.getId(), storage().policies().findOneById(created.getId(), owner).getId());
    }

    @Test
    default void policyNamesAreUniquePerUser() {
        final String owner = RecordIds.newId();
        createPolicy(owner, "claims", POLICY_A);
        final PolicyDataService.WriteOutcome again = storage().policies()
                .createPolicy("req", owner, POLICY_B, null, null, "claims", "test", owner);
        assertFalse(again.response().isSuccessful());
        assertEquals(409, again.response().getStatusCode());
        assertEquals(POLICY_A, storage().policies().findOne("claims", owner).getPolicy());
        // Another user may use the same name.
        createPolicy(RecordIds.newId(), "claims", POLICY_B);
    }

    @Test
    default void policyConditionalWriteAppliesAtTheTagItWasReadAt() {
        final String owner = RecordIds.newId();
        final PolicyEntity created = createPolicy(owner, "claims", POLICY_A);
        final PolicyDataService.WriteOutcome written = storage().policies().replace("req", owner, created.getId(),
                POLICY_B, null, null, "test", owner, PolicyDataService.eTag(created));
        assertTrue(written.response().isSuccessful(), written.response().getMessage());
        assertNotEquals(PolicyDataService.eTag(created), PolicyDataService.eTag(written.policy()));
        assertEquals(POLICY_B, storage().policies().findOne("claims", owner).getPolicy());
    }

    @Test
    default void policyConditionalWriteAtAStaleTagWritesNothing() {
        final String owner = RecordIds.newId();
        final PolicyEntity created = createPolicy(owner, "claims", POLICY_A);
        final String readTag = PolicyDataService.eTag(created);
        storage().policies().updateDetails("req", owner, "claims", "changed elsewhere", null, "test");
        final PolicyDataService.WriteOutcome stale = storage().policies().replace("req", owner, created.getId(),
                POLICY_B, null, null, "test", owner, readTag);
        assertFalse(stale.response().isSuccessful());
        assertEquals(PolicyDataService.REASON_POLICY_CHANGED, stale.response().getDetails());
        assertNull(stale.policy());
        final PolicyEntity stored = storage().policies().findOne("claims", owner);
        assertEquals(POLICY_A, stored.getPolicy());
        assertEquals("changed elsewhere", stored.getDescription());
    }

    @Test
    default void policyConditionalWritesRacingWithOneTagLetExactlyOneThrough() throws Exception {
        final String owner = RecordIds.newId();
        final PolicyEntity created = createPolicy(owner, "claims", POLICY_A);
        final String tag = PolicyDataService.eTag(created);
        final AtomicInteger next = new AtomicInteger();
        final List<Boolean> results = StorageContract.concurrently(6, () -> storage().policies().replace("req", owner,
                created.getId(), POLICY_B, "writer " + next.incrementAndGet(), null, "test", owner, tag)
                .response().isSuccessful());
        assertEquals(1, results.stream().filter(ok -> ok).count(), results.toString());
    }

    @Test
    default void policyRevisionsAreNeverReusedAfterDeletingAndCreatingAgain() {
        final String owner = RecordIds.newId();
        final PolicyEntity created = createPolicy(owner, "claims", POLICY_A);
        final PolicyEntity changed = storage().policies().replace("req", owner, created.getId(), POLICY_B, null, null,
                "test", owner, null).policy();
        final int highest = changed.getRevision();
        assertTrue(storage().policies().deleteByName("req", "claims", owner, Source.API, owner, null).isSuccessful());
        final PolicyEntity recreated = createPolicy(owner, "claims", POLICY_A);
        assertTrue(recreated.getRevision() > highest, recreated.getRevision() + " after " + highest);
    }

    @Test
    default void policyContentChangesAreSnapshottedSoEveryRevisionResolves() {
        final String owner = RecordIds.newId();
        final PolicyEntity created = createPolicy(owner, "claims", POLICY_A);
        final PolicyEntity changed = storage().policies().replace("req", owner, created.getId(), POLICY_B, null, null,
                "test", owner, null).policy();
        assertEquals(POLICY_A, storage().policyVersions().findByNameAndRevision("claims", owner, created.getRevision()).getPolicy());
        assertEquals(POLICY_B, storage().policyVersions().findByNameAndRevision("claims", owner, changed.getRevision()).getPolicy());
    }

    @Test
    default void policyListingsCountPageSortAndSearch() {
        final String owner = RecordIds.newId();
        for (final String name : List.of("alpha", "Bravo", "charlie", "delta", "echo", "foxtrot", "golf")) {
            createPolicy(owner, name, POLICY_A);
        }
        createPolicy(RecordIds.newId(), "hotel", POLICY_A);
        final Listings.Sort byName = new Listings.Sort("name", false);
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 7; offset += 3) {
            final Listings.Page<PolicyEntity> page = storage().policies().listOwn(owner, null, byName, offset, 3);
            assertEquals(7, page.total());
            page.items().forEach(policy -> assertTrue(seen.add(policy.getName()), "repeated " + policy.getName()));
        }
        assertEquals(7, seen.size());
        final List<String> descending = storage().policies().listOwn(owner, null, new Listings.Sort("name", true), 0, 2)
                .items().stream().map(PolicyEntity::getName).toList();
        assertEquals(List.of("golf", "foxtrot"), descending);
        final Listings.Page<PolicyEntity> found = storage().policies().listOwn(owner, "BRAV", byName, 0, 10);
        assertEquals(1, found.total());
        assertEquals("Bravo", found.items().get(0).getName());
        assertNotNull(storage().policies().listOwn(owner, ".*", byName, 0, 10));
        assertEquals(0, storage().policies().listOwn(owner, ".*", byName, 0, 10).total(), "search is plain text");
    }

}
