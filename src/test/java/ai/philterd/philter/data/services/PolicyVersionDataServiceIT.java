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
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link PolicyVersionDataService} against a real (in-memory) MongoDB. These
 * exercise the content-addressed, append-only retention of policy versions that the redaction-evidence
 * feature depends on: snapshots are de-duplicated by content hash, resolvable by hash, and never
 * removed when the live policy is edited or deleted.
 */
class PolicyVersionDataServiceIT extends AbstractMongoIT {

    private PolicyVersionDataService versionService;

    @BeforeEach
    void setUpService() {
        versionService = new PolicyVersionDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    private static PolicyEntity policy(final String name, final int revision, final String json, final ObjectId userId) {
        final PolicyEntity entity = new PolicyEntity();
        entity.setName(name);
        entity.setRevision(revision);
        entity.setPolicy(json);
        entity.setUserId(userId);
        return entity;
    }

    @Test
    void snapshotIsContentAddressedIdempotentAndResolvableByHash() {
        final ObjectId user = new ObjectId();
        final String json = "{\"identifiers\":{\"person\":{}}}";

        final String hash = versionService.snapshot(policy("p", 1, json, user));
        assertNotNull(hash);

        // Snapshotting the same content again (even under a different revision) is a no-op: one row.
        final String hashAgain = versionService.snapshot(policy("p", 2, json, user));
        assertEquals(hash, hashAgain);

        final PolicyVersionEntity resolved = versionService.findByContentHash(hash);
        assertNotNull(resolved);
        assertEquals(json, resolved.getPolicy());
        assertEquals("p", resolved.getName());
        // The first capture's revision is retained.
        assertEquals(1, resolved.getRevision());
    }

    @Test
    void differentContentYieldsDifferentSnapshots() {
        final ObjectId user = new ObjectId();
        final String hashA = versionService.snapshot(policy("p", 1, "{\"identifiers\":{\"a\":{}}}", user));
        final String hashB = versionService.snapshot(policy("p", 2, "{\"identifiers\":{\"b\":{}}}", user));

        assertNotEquals(hashA, hashB);
        assertNotNull(versionService.findByContentHash(hashA));
        assertNotNull(versionService.findByContentHash(hashB));
    }

    @Test
    void snapshotOfBlankPolicyIsSkipped() {
        assertEquals(null, versionService.snapshot(policy("p", 1, null, new ObjectId())));
        assertEquals(null, versionService.snapshot(policy("p", 1, "  ", new ObjectId())));
    }

    @Test
    void retainedVersionsSurviveLivePolicyDeletion() {
        final ObjectId user = new ObjectId();
        final String json = "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

        // A real PolicyDataService writes a snapshot on create and must not remove it on delete.
        final PolicyDataService policyService = new PolicyDataService(
                mongoClient, mock(AuditEventPublisher.class), new Gson(), versionService);

        assertTrue(policyService.create("req", user, json, "desc", "notes", "evidence-policy", "system").isSuccessful());
        final String hash = PolicyVersionDataService.contentHash(json);
        assertNotNull(versionService.findByContentHash(hash), "create must retain a version snapshot");

        assertTrue(policyService.deleteByName("req", "evidence-policy", user, Source.WEBUI).isSuccessful());

        // The live policy is gone, but the retained evidence remains resolvable.
        assertNotNull(versionService.findByContentHash(hash), "deleting the live policy must not remove retained versions");
    }
}
