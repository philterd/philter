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
import ai.philterd.philter.data.entities.PolicyVersionEntity;

import java.util.Date;
import java.util.List;

/**
 * The rules for policy versions, the same in every storage implementation: content is kept once by its hash and
 * checked against it when read, and a revision, once recorded, always refers to the same content. An
 * implementation stores the content and the revisions.
 */
public abstract class StoredPolicyVersionDataService implements PolicyVersionDataService {

    /**
     * Stores policy content under its hash.
     *
     * @throws DuplicateRecordException when content with this hash is already stored.
     */
    protected abstract void insertContent(final String contentHash, final String policyJson);

    /** The policy content stored under this hash, or null. */
    protected abstract String findContent(final String contentHash);

    /**
     * Stores a revision.
     *
     * @throws DuplicateRecordException when the owner's policy already has this revision.
     */
    protected abstract void insertVersion(final PolicyVersionEntity version);

    @Override
    public String snapshot(final PolicyEntity policyEntity) {

        if (policyEntity == null) {
            return null;
        }

        final String policyJson = policyEntity.getPolicy();
        final String hash = PolicyVersionDataService.contentHash(policyJson);
        if (hash == null) {
            return null;
        }

        // Persist the content independently of the revision reference. Only a duplicate is idempotent;
        // authorization, validation and storage failures must reach the caller.
        try {
            insertContent(hash, policyJson);
        } catch (final DuplicateRecordException e) {
            if (!hash.equals(PolicyVersionDataService.contentHash(findContent(hash)))) throw e;
        }
        final PolicyVersionEntity existing = findByNameAndRevision(policyEntity.getName(),
                policyEntity.getUserId(), policyEntity.getRevision());
        if (existing != null) {
            if (!hash.equals(existing.getContentHash())) {
                throw new IllegalStateException("Policy revision already refers to different content.");
            }
            return hash;
        }

        final PolicyVersionEntity version = new PolicyVersionEntity();
        version.setName(policyEntity.getName());
        version.setRevision(policyEntity.getRevision());
        version.setContentHash(hash);
        version.setPolicy(policyJson);
        version.setUserId(policyEntity.getUserId());
        version.setCapturedTimestamp(new Date());
        version.setAuthorId(policyEntity.getAuthorId());

        try {
            insertVersion(version);
        } catch (final DuplicateRecordException e) {
            final PolicyVersionEntity winner = findByNameAndRevision(policyEntity.getName(),
                    policyEntity.getUserId(), policyEntity.getRevision());
            if (winner == null || !hash.equals(winner.getContentHash())) throw e;
        }

        return hash;
    }

    @Override
    public PolicyVersionEntity findByContentHash(final String contentHash) {
        if (contentHash == null) {
            return null;
        }
        final String policy = findContent(contentHash);
        if (policy == null) return null;
        if (!contentHash.equals(PolicyVersionDataService.contentHash(policy))) {
            throw new IllegalStateException("Policy content does not match its fingerprint.");
        }
        final PolicyVersionEntity result = new PolicyVersionEntity();
        result.setContentHash(contentHash);
        result.setPolicy(policy);
        return result;
    }

    @Override
    public List<PolicyVersionEntity> findTwoMostRecent(final String name, final String userId) {
        return findAllByName(name, userId, 0, 2);
    }

}
