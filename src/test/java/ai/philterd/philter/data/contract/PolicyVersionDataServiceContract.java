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
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The guarantees of {@link PolicyVersionDataService}. */
public interface PolicyVersionDataServiceContract extends StorageContract {

    private static PolicyEntity policy(final String owner, final String name, final int revision, final String json) {
        final PolicyEntity policy = new PolicyEntity();
        policy.setId(RecordIds.newId());
        policy.setUserId(owner);
        policy.setName(name);
        policy.setRevision(revision);
        policy.setPolicy(json);
        return policy;
    }

    @Test
    default void policyVersionsAreImmutable() {
        final String owner = RecordIds.newId();
        storage().policyVersions().snapshot(policy(owner, "claims", 1, "{\"identifiers\":{}}"));
        // The same content again is accepted; different content for the revision is refused.
        storage().policyVersions().snapshot(policy(owner, "claims", 1, "{\"identifiers\":{}}"));
        assertThrows(IllegalStateException.class,
                () -> storage().policyVersions().snapshot(policy(owner, "claims", 1, "{\"identifiers\":{\"x\":{}}}")));
        assertEquals("{\"identifiers\":{}}", storage().policyVersions().findByNameAndRevision("claims", owner, 1).getPolicy());
    }

    @Test
    default void policyVersionsOfOneRevisionWrittenAtOnceKeepOneContent() throws Exception {
        final String owner = RecordIds.newId();
        final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.List<String> outcomes = StorageContract.concurrently(4, () -> {
            final String content = "{\"identifiers\":{\"n" + next.getAndIncrement() + "\":{}}}";
            try {
                storage().anotherInstance().policyVersions().snapshot(policy(owner, "claims", 1, content));
                return content;
            } catch (final IllegalStateException refused) {
                return "refused";
            }
        });
        assertEquals(3, outcomes.stream().filter("refused"::equals).count(), outcomes.toString());
        final String kept = outcomes.stream().filter(outcome -> !"refused".equals(outcome)).findFirst().orElseThrow();
        assertEquals(kept, storage().policyVersions().findByNameAndRevision("claims", owner, 1).getPolicy());
    }

    @Test
    default void policyContentHashesIgnoreKeyOrderAndFormatting() {
        assertEquals(PolicyVersionDataService.contentHash("{\"a\":1,\"b\":[1,2]}"),
                PolicyVersionDataService.contentHash("{ \"b\" : [1, 2], \"a\" : 1 }"));
        final String owner = RecordIds.newId();
        storage().policyVersions().snapshot(policy(owner, "claims", 1, "{\"identifiers\":{}}"));
        // Content is kept once by its hash, whichever revisions share it.
        assertEquals("{\"identifiers\":{}}", storage().policyVersions()
                .findByContentHash(PolicyVersionDataService.contentHash("{ \"identifiers\" : {} }")).getPolicy());
    }

    @Test
    default void policyVersionListingsCountAndOrderByRevision() {
        final String owner = RecordIds.newId();
        for (int revision = 1; revision <= 5; revision++) {
            storage().policyVersions().snapshot(policy(owner, "claims", revision, "{\"identifiers\":{},\"r\":" + revision + "}"));
        }
        final Listings.Page<PolicyVersionEntity> newest = storage().policyVersions().listByName("claims", owner, true, 0, 2);
        assertEquals(5, newest.total());
        assertEquals(List.of(5, 4), newest.items().stream().map(PolicyVersionEntity::getRevision).toList());
        assertEquals(List.of(3, 4, 5), storage().policyVersions().listByName("claims", owner, false, 2, 10)
                .items().stream().map(PolicyVersionEntity::getRevision).toList());
    }

}
