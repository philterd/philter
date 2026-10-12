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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** MongoDB implementation of {@link PolicyVersionDataService}. */
public class MongoPolicyVersionDataService extends AbstractService<PolicyVersionEntity> implements PolicyVersionDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyVersionDataService.class);

    /** Serializes canonicalized policy JSON compactly for hashing. */
    private final com.mongodb.client.MongoCollection<Document> contents;

    /** When each of a user's policies was last deleted, and by whom, so deleted policies can be listed. */
    private final com.mongodb.client.MongoCollection<Document> deletions;

    public MongoPolicyVersionDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "policy_versions", auditEventPublisher);

        contents = mongoClient.getDatabase("philter").getCollection("policy_contents");
        deletions = mongoClient.getDatabase("philter").getCollection("policy_deletions");
        RequiredSchema.ensureIndex(deletions, Indexes.ascending("user_id", "name"), new IndexOptions().unique(true));

        // A snapshot is identified by the revision it was taken at, not by its content: the same
        // content legitimately reappears at a later revision, through a rollback or an edit that
        // restores an earlier policy.
        ensureIndex(Indexes.ascending("user_id", "name", "revision"), new IndexOptions().unique(true));
        // Resolving a pinned policy by its fingerprint, which several revisions may share.
        ensureIndex(Indexes.ascending("content_hash"));
    }

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

        // Persist the content independently of the revision reference. Only duplicate-key errors
        // are idempotent; authorization, validation and storage failures must reach the caller.
        try {
            contents.insertOne(new Document("_id", hash).append("policy", policyJson));
        } catch (final com.mongodb.MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw e;
            final Document existing = contents.find(Filters.eq("_id", hash)).first();
            if (existing == null || !hash.equals(PolicyVersionDataService.contentHash(existing.getString("policy")))) throw e;
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
            save(version);
        } catch (final com.mongodb.MongoWriteException e) {
            if (e.getError().getCode() != 11000) throw e;
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
        final Document document = contents.find(Filters.eq("_id", contentHash)).first();
        if (document == null) return null;
        if (!contentHash.equals(PolicyVersionDataService.contentHash(document.getString("policy")))) {
            throw new IllegalStateException("Policy content does not match its fingerprint.");
        }
        final PolicyVersionEntity result = new PolicyVersionEntity();
        result.setContentHash(contentHash);
        result.setPolicy(document.getString("policy"));
        return result;
    }

    @Override
    public List<PolicyVersionEntity> findAllByName(final String name, final String userId,
                                                    final int offset, final int limit) {
        final Bson query = Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("name", name));
        final FindIterable<Document> documents = collection.find(query)
                .sort(Sorts.descending("revision"))
                .skip(offset)
                .limit(limit);
        final List<PolicyVersionEntity> versions = new ArrayList<>();
        for (final Document doc : documents) {
            versions.add(PolicyVersionDocuments.fromDocument(doc));
        }
        return versions;
    }

    @Override
    public Listings.Page<PolicyVersionEntity> listByName(final String name, final String userId,
                                                         final boolean descending, final int offset, final int limit) {
        return MongoListings.page(collection, Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("name", name)),
                new Listings.Sort("revision", descending), offset, limit, PolicyVersionDocuments::fromDocument);
    }

    @Override
    public PolicyVersionEntity findByNameAndRevision(final String name, final String userId,
                                                      final int revision) {
        final Bson query = Filters.and(
                Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("name", name),
                Filters.eq("revision", revision));
        final Document document = collection.find(query).first();
        return document != null ? PolicyVersionDocuments.fromDocument(document) : null;
    }

    @Override
    public List<PolicyVersionEntity> findTwoMostRecent(final String name, final String userId) {
        return findAllByName(name, userId, 0, 2);
    }

    @Override
    public void recordDeletion(final String userId, final String name, final int revision,
                               final String deletedBy, final Date deletedAt) {
        deletions.updateOne(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("name", name)),
                new Document("$set", new Document("revision", revision).append("deleted_by", MongoIds.objectId(deletedBy))
                        .append("deleted_at", deletedAt)),
                new com.mongodb.client.model.UpdateOptions().upsert(true));
    }

    @Override
    public List<String> findNames(final String userId) {
        final List<String> names = new ArrayList<>();
        collection.distinct("name", Filters.eq("user_id", MongoIds.objectId(userId)), String.class).into(names);
        return names;
    }

    @Override
    public Deletion findDeletion(final String userId, final String name) {
        final Document deletion = deletions.find(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("name", name))).first();
        return deletion == null ? null
                : new Deletion(deletion.getDate("deleted_at"), MongoIds.hex(deletion.getObjectId("deleted_by")));
    }

    @Override
    public void update(PolicyVersionEntity version) {
        throw new UnsupportedOperationException("Policy versions are immutable.");
    }
    @Override
    protected Document toDocument(final PolicyVersionEntity entity) {
        return PolicyVersionDocuments.toDocument(entity);
    }

}
