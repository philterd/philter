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
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
 */
public class PolicyVersionDataService extends AbstractService<PolicyVersionEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyVersionDataService.class);

    public PolicyVersionDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "policy_versions", auditEventPublisher);

        // Snapshots are resolved by content hash; the hash is the de-duplication key (one snapshot per
        // distinct policy content).
        ensureIndex(Indexes.ascending("content_hash"), new IndexOptions().unique(true));
        // Browsing a policy's retained versions by name/revision.
        ensureIndex(Indexes.ascending("user_id", "name", "revision"));
    }

    /**
     * Computes the content hash (SHA-256) of a policy's JSON. This is the fingerprint stamped onto
     * ledger entries and used to resolve the retained snapshot.
     *
     * @return the hex SHA-256 of the policy JSON, or {@code null} if the JSON is null/blank.
     */
    public static String contentHash(final String policyJson) {
        if (policyJson == null || policyJson.isBlank()) {
            return null;
        }
        return EncryptionService.hashSha256(policyJson);
    }

    /**
     * Records an immutable snapshot of the policy's current content, if one does not already exist for
     * that content. Idempotent and keyed by content hash, so calling it repeatedly (or for unchanged
     * content) is safe and cheap. A policy with no JSON body is skipped.
     *
     * @return the content hash of the snapshot, or {@code null} if there was nothing to snapshot.
     */
    public String snapshot(final PolicyEntity policyEntity) {

        if (policyEntity == null) {
            return null;
        }

        final String policyJson = policyEntity.getPolicy();
        final String hash = contentHash(policyJson);
        if (hash == null) {
            return null;
        }

        // Idempotent: one immutable snapshot per distinct content. The unique index on content_hash is
        // the backstop against a race.
        if (findByContentHash(hash) != null) {
            return hash;
        }

        final PolicyVersionEntity version = new PolicyVersionEntity();
        version.setName(policyEntity.getName());
        version.setRevision(policyEntity.getRevision());
        version.setContentHash(hash);
        version.setPolicy(policyJson);
        version.setUserId(policyEntity.getUserId());
        version.setCapturedTimestamp(new Date());

        try {
            save(version);
        } catch (final com.mongodb.MongoWriteException e) {
            // A concurrent snapshot of identical content won the race; the version is retained either
            // way, so treat a duplicate-key collision as success.
            LOGGER.debug("Policy version snapshot for hash {} already exists: {}", hash, e.getMessage());
        }

        return hash;
    }

    /** Resolves a retained snapshot by its content hash, or {@code null} if none has been retained. */
    public PolicyVersionEntity findByContentHash(final String contentHash) {
        if (contentHash == null) {
            return null;
        }
        final Document document = collection.find(Filters.eq("content_hash", contentHash)).first();
        return document != null ? PolicyVersionEntity.fromDocument(document) : null;
    }

    /**
     * Returns a page of retained snapshots for the given policy name and owner, ordered by revision
     * descending (most recent first). The {@code (user_id, name, revision)} compound index covers this
     * query efficiently.
     */
    public List<PolicyVersionEntity> findAllByName(final String name, final ObjectId userId,
                                                    final int offset, final int limit) {
        final Bson query = Filters.and(Filters.eq("user_id", userId), Filters.eq("name", name));
        final FindIterable<Document> documents = collection.find(query)
                .sort(Sorts.descending("revision"))
                .skip(offset)
                .limit(limit);
        final List<PolicyVersionEntity> versions = new ArrayList<>();
        for (final Document doc : documents) {
            versions.add(PolicyVersionEntity.fromDocument(doc));
        }
        return versions;
    }

    /**
     * Resolves a specific retained snapshot by policy name, owner, and revision number, or
     * {@code null} if no snapshot for that revision has been retained.
     */
    public PolicyVersionEntity findByNameAndRevision(final String name, final ObjectId userId,
                                                      final int revision) {
        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("name", name),
                Filters.eq("revision", revision));
        final Document document = collection.find(query).first();
        return document != null ? PolicyVersionEntity.fromDocument(document) : null;
    }

    /**
     * Returns the two most recent retained snapshots for the given policy, ordered by revision
     * descending. Returns an empty list if there are no snapshots, and a single-element list if only
     * one snapshot exists.
     */
    public List<PolicyVersionEntity> findTwoMostRecent(final String name, final ObjectId userId) {
        return findAllByName(name, userId, 0, 2);
    }

}
