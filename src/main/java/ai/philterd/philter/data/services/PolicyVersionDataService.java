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
 * Stores immutable, append-only snapshots of policy content ({@link PolicyVersionEntity}) so that the
 * policy version stamped onto a redaction ledger entry can be resolved back to the exact policy that
 * governed the redaction.
 *
 * <p>Snapshots are content-addressed: the SHA-256 of the policy JSON is the de-duplication key, so
 * re-saving identical content is a no-op and a recreated policy that reuses a name does not collide
 * with prior evidence. Snapshots are evidence, decoupled from the live policy lifecycle: this service
 * intentionally exposes no method to delete a snapshot when a live policy is edited or deleted.
 * Removing retained versions is a separate, deliberate, audited action (governed by issue #249).
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A snapshot is immutable: a revision is unique per user and policy name, and writing different
 *   content for an existing revision is refused.</li>
 *   <li>Snapshots never expire on their own.</li>
 *   <li>The content hash is computed with {@link #contentHash}, the same in every implementation, since ledger
 *   entries carry it.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping revisions, and order by
 *   revision.</li>
 * </ul>
 */
public interface PolicyVersionDataService {

    /**
     * Computes the content hash (SHA-256) of a policy's JSON. This is the fingerprint stamped onto
     * ledger entries and used to resolve the retained snapshot.
     *
     * <p>The JSON is canonicalized before hashing (object keys sorted recursively, insignificant
     * whitespace removed) so that two otherwise-identical policies that differ only in key order or
     * formatting produce the same fingerprint. If the input cannot be parsed as JSON it is hashed
     * as-is, so a malformed policy still yields a stable hash.
     *
     * @return the hex SHA-256 of the canonicalized policy JSON, or {@code null} if the JSON is null/blank.
     */
    static String contentHash(final String policyJson) {
        return PolicyContentHashes.of(policyJson);
    }

    /**
     * Retains content by hash and an immutable owner/name/revision reference. Repeated identical
     * snapshots are idempotent; conflicting content for an existing revision is rejected.
     */
    String snapshot(final PolicyEntity policyEntity);

    /** Any retained snapshot with this content, since several revisions may share it. */
    PolicyVersionEntity findByContentHash(final String contentHash);

    /**
     * Returns a page of retained snapshots for the given policy name and owner, ordered by revision
     * descending (most recent first).
     */
    List<PolicyVersionEntity> findAllByName(final String name, final String userId,
            final int offset, final int limit);

    /** A page of the policy's retained versions, by revision, and how many there are. */
    Listings.Page<PolicyVersionEntity> listByName(final String name, final String userId,
            final boolean descending, final int offset, final int limit);

    /**
     * Resolves a specific retained snapshot by policy name, owner, and revision number, or
     * {@code null} if no snapshot for that revision has been retained.
     */
    PolicyVersionEntity findByNameAndRevision(final String name, final String userId,
            final int revision);

    /**
     * Returns the two most recent retained snapshots for the given policy, ordered by revision
     * descending. Returns an empty list if there are no snapshots, and a single-element list if only
     * one snapshot exists.
     */
    List<PolicyVersionEntity> findTwoMostRecent(final String name, final String userId);

    /**
     * Records that a user's policy was deleted, replacing any earlier deletion of a policy with that
     * name, so the most recent deletion is the one reported.
     */
    void recordDeletion(final String userId, final String name, final int revision,
            final String deletedBy, final Date deletedAt);

    /** The names of every policy the user has retained versions of, deleted or not. */
    List<String> findNames(final String userId);

    /** When a policy was deleted and who deleted it; either may be {@code null} when it was not recorded. */
    record Deletion(Date deletedAt, String deletedBy) {
    }

    /** The user's most recent deletion of a policy with this name, or {@code null} when none was recorded. */
    Deletion findDeletion(final String userId, final String name);

}
