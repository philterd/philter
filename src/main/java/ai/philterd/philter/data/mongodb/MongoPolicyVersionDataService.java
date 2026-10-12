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
import ai.philterd.philter.data.services.DuplicateRecordException;
import ai.philterd.philter.data.services.StoredPolicyVersionDataService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Stores policy versions in MongoDB: content by its hash, and a document per revision. */
public class MongoPolicyVersionDataService extends StoredPolicyVersionDataService {

    private final MongoRecords<PolicyVersionEntity> records;
    private final com.mongodb.client.MongoCollection<Document> collection;

    /** Policy content, by its hash. */
    private final com.mongodb.client.MongoCollection<Document> contents;

    /** When each of a user's policies was last deleted, and by whom, so deleted policies can be listed. */
    private final com.mongodb.client.MongoCollection<Document> deletions;

    public MongoPolicyVersionDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        records = new MongoRecords<>(mongoClient, "policy_versions", PolicyVersionDocuments::toDocument);
        collection = records.collection;

        contents = mongoClient.getDatabase("philter").getCollection("policy_contents");
        deletions = mongoClient.getDatabase("philter").getCollection("policy_deletions");
        RequiredSchema.ensureIndex(deletions, Indexes.ascending("user_id", "name"), new IndexOptions().unique(true));

        // A snapshot is identified by the revision it was taken at, not by its content: the same
        // content legitimately reappears at a later revision, through a rollback or an edit that
        // restores an earlier policy.
        records.ensureIndex(Indexes.ascending("user_id", "name", "revision"), new IndexOptions().unique(true));
        // Resolving a pinned policy by its fingerprint, which several revisions may share.
        records.ensureIndex(Indexes.ascending("content_hash"));
    }

    @Override
    protected void insertContent(final String contentHash, final String policyJson) {
        try {
            contents.insertOne(new Document("_id", contentHash).append("policy", policyJson));
        } catch (final com.mongodb.MongoWriteException e) {
            throw MongoRecords.isDuplicate(e) ? new DuplicateRecordException(e) : e;
        }
    }

    @Override
    protected String findContent(final String contentHash) {
        final Document document = contents.find(Filters.eq("_id", contentHash)).first();
        return document == null ? null : document.getString("policy");
    }

    @Override
    protected void insertVersion(final PolicyVersionEntity version) {
        records.insert(version);
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

}
