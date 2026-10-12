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
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.StoredPolicyDataService;
import ai.philterd.philter.services.cache.RedactionCache;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stores policies in MongoDB, one document per policy, with a revision counter per owner and name. */
public class MongoPolicyDataService extends StoredPolicyDataService {

    private final MongoRecords<PolicyEntity> records;
    private final MongoCollection<Document> collection;
    private final MongoCollection<Document> revisionCounters;

    public MongoPolicyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final Gson gson, final PolicyVersionDataService policyVersionDataService, final RedactionCache redactionCache) {
        super(auditEventPublisher, gson, policyVersionDataService, redactionCache);
        this.records = new MongoRecords<>(mongoClient, "policies", PolicyDocuments::toDocument);
        this.collection = records.collection;
        this.revisionCounters = mongoClient.getDatabase("philter").getCollection("policy_revision_counters");

        // User policies are listed/looked up by (user_id, name); managed policies by (managed, name).
        records.ensureIndex(Indexes.ascending("user_id", "name"), new IndexOptions().unique(true));
        records.ensureIndex(Indexes.ascending("managed", "name"));
        // Listing a user's policies also matches shared policies via an OR branch on shared=true, so
        // index shared so that branch uses an index instead of scanning the collection.
        records.ensureIndex(Indexes.ascending("shared", "name"));
    }

    @Override
    protected int reserveRevision(final String userId, final String name, final int minimum) {
        final Document identity = new Document("user_id", MongoIds.objectId(userId)).append("name", name);
        // Reserve above the observed head, even when a competing candidate consumed a revision.
        try {
            revisionCounters.updateOne(Filters.eq("_id", identity),
                    new Document("$max", new Document("next", minimum)), new UpdateOptions().upsert(true));
        } catch (final com.mongodb.MongoWriteException race) {
            if (!MongoRecords.isDuplicate(race)) throw race;
            revisionCounters.updateOne(Filters.eq("_id", identity), new Document("$max", new Document("next", minimum)));
        }
        final Document result = revisionCounters.findOneAndUpdate(Filters.eq("_id", identity),
                new Document("$inc", new Document("next", 1)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return result.getInteger("next") - 1;
    }

    @Override
    protected String insert(final PolicyEntity policy) {
        return records.insert(policy);
    }

    private static Bson unchanged(final int revision, final String description, final String notes) {
        return Filters.and(Filters.eq("revision", revision), Filters.eq("description", description),
                Filters.eq("notes", notes));
    }

    @Override
    protected boolean replaceIfUnchanged(final PolicyEntity policy, final int expectedRevision,
                                         final String expectedDescription, final String expectedNotes) {
        return collection.replaceOne(Filters.and(Filters.eq("_id", MongoIds.objectId(policy.getId())),
                unchanged(expectedRevision, expectedDescription, expectedNotes)), PolicyDocuments.toDocument(policy)).getMatchedCount() == 1;
    }

    @Override
    protected boolean replaceIfAtRevision(final PolicyEntity policy, final int expectedRevision) {
        return collection.replaceOne(Filters.and(Filters.eq("_id", MongoIds.objectId(policy.getId())),
                Filters.eq("revision", expectedRevision)), PolicyDocuments.toDocument(policy)).getMatchedCount() == 1;
    }

    @Override
    protected boolean updateDetailsIfUnchanged(final PolicyEntity policy, final String description, final String notes,
                                               final Date now) {
        final List<Bson> updates = new ArrayList<>();
        if (description != null) {
            updates.add(Updates.set("description", description));
        }
        if (notes != null) {
            updates.add(Updates.set("notes", notes));
        }
        updates.add(Updates.set("last_updated_timestamp", now));
        return collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(policy.getId())),
                        Filters.eq("user_id", MongoIds.objectId(policy.getUserId())),
                        unchanged(policy.getRevision(), policy.getDescription(), policy.getNotes())),
                Updates.combine(updates)).getMatchedCount() == 1;
    }

    @Override
    protected boolean delete(final String userId, final String name) {
        // A managed policy has no owner; the filter is a safeguard all the same.
        return collection.deleteOne(new Document("name", name).append("managed", false)
                .append("user_id", MongoIds.objectId(userId))).getDeletedCount() == 1;
    }

    @Override
    protected PolicyEntity findManaged(final String name) {
        return first(Filters.and(Filters.eq("name", name), Filters.eq("managed", true)));
    }

    @Override
    protected Set<String> findNames(final String userId) {
        final Set<String> names = new HashSet<>();
        collection.distinct("name", Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.ne("managed", true)), String.class)
                .into(names);
        return names;
    }

    private PolicyEntity first(final Bson filter) {
        final Document document = collection.find(filter).first();
        return document == null ? null : PolicyDocuments.fromDocument(document);
    }

    @Override
    public PolicyEntity findOne(final String name, final String userId) {
        return first(Filters.and(Filters.eq("name", name), Filters.eq("user_id", MongoIds.objectId(userId))));
    }

    @Override
    public PolicyEntity findOneById(final String id, final String userId) {
        return first(Filters.and(Filters.eq("_id", MongoIds.objectId(id)),
                Filters.or(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("shared", true))));
    }

    @Override
    public Listings.Page<PolicyEntity> listOwn(final String userId, final String q, final Listings.Sort sort,
                                               final int offset, final int limit) {
        return MongoListings.page(collection, MongoListings.all(Filters.or(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("shared", true)),
                Filters.ne("managed", true), MongoListings.contains("name", q)), sort, offset, limit, PolicyDocuments::fromDocument);
    }

    @Override
    public Listings.Page<PolicyEntity> listAcrossUsers(final String q, final Listings.Sort sort, final int offset,
                                                       final int limit) {
        return MongoListings.page(collection, MongoListings.all(Filters.ne("managed", true), MongoListings.contains("name", q)), sort, offset,
                limit, PolicyDocuments::fromDocument);
    }

    @Override
    public Listings.Page<PolicyEntity> listManaged(final String q, final Listings.Sort sort, final int offset,
                                                   final int limit) {
        return MongoListings.page(collection, MongoListings.all(Filters.eq("managed", true), MongoListings.contains("name", q)), sort,
                offset, limit, PolicyDocuments::fromDocument);
    }

    @Override
    public int count(final String userId) {
        return (int) collection.countDocuments(userId == null ? Filters.eq("managed", false)
                : Filters.and(Filters.eq("managed", false), Filters.eq("user_id", MongoIds.objectId(userId))));
    }

}
