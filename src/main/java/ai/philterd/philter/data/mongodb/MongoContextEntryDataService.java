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

import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores context entries in MongoDB, one document per token and context. */
public class MongoContextEntryDataService extends StoredContextEntryDataService {

    private final MongoCollection<Document> collection;

    public MongoContextEntryDataService(final MongoClient mongoClient, final ContextCache contextCache) {
        super(contextCache);
        final MongoRecords<ContextEntryEntity> records =
                new MongoRecords<>(mongoClient, "context_entries", ContextEntryDocuments::toDocument);
        this.collection = records.collection;

        // Token lookups during redaction hit (user_id, context_name, token_hash); listing/eviction
        // scans (user_id, context_name) ordered by timestamp/reads.
        ensureUniqueTokenIndex();
        records.ensureIndex(Indexes.ascending("user_id", "context_name", "timestamp"));
    }

    /**
     * The token index is unique, so a second concurrent insert is refused rather than stored. Unlike
     * the other indexes this one carries a guarantee the code relies on, so a failure to create it is
     * fatal instead of a warning: running without it looks identical and silently allows duplicates.
     */
    private void ensureUniqueTokenIndex() {
        try {
            collection.createIndex(Indexes.ascending("user_id", "context_name", "token_hash"),
                    new IndexOptions().unique(true));
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to create the unique token index on 'context_entries'. "
                    + "Duplicate entries for one token may already exist; remove them and restart.", ex);
        }
    }

    private MongoContextEntryDataService(final MongoCollection<Document> collection, final ContextCache contextCache,
                                         final int capacity) {
        super(contextCache, capacity);
        this.collection = collection;
    }

    /** The same stored entries, with contexts that hold at most {@code capacity} entries, for tests. */
    MongoContextEntryDataService withCapacity(final ContextCache contextCache, final int capacity) {
        return new MongoContextEntryDataService(collection, contextCache, capacity);
    }

    @Override
    public MongoContextEntryDataService withCache(final ContextCache contextCache) {
        return new MongoContextEntryDataService(collection, contextCache, capacity());
    }

    private static Bson inContext(final String userId, final String contextName) {
        return Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("context_name", contextName));
    }

    private static Bson byTokenHash(final String userId, final String contextName, final String tokenHash) {
        return Filters.and(inContext(userId, contextName), Filters.eq("token_hash", tokenHash));
    }

    @Override
    protected ContextEntryEntity insertIfAbsent(final ContextEntryEntity entry) {

        final Bson query = byTokenHash(entry.getUserId(), entry.getContextName(), entry.getTokenHash());

        final Document document = ContextEntryDocuments.toDocument(entry);
        final List<Bson> inserts = new ArrayList<>();
        document.forEach((field, value) -> inserts.add(Updates.setOnInsert(field, value)));

        try {
            final Document stored = collection.findOneAndUpdate(query, Updates.combine(inserts),
                    new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
            return stored != null ? ContextEntryDocuments.fromDocument(stored) : null;
        } catch (final MongoWriteException | MongoCommandException lostTheRace) {
            // The unique index rejected a second insert; the winner's row is what counts.
            final Document winner = collection.find(query).first();
            return winner != null ? ContextEntryDocuments.fromDocument(winner) : null;
        }

    }

    @Override
    protected ContextEntryEntity findByTokenHash(final String userId, final String contextName, final String tokenHash) {
        final Document document = collection.find(byTokenHash(userId, contextName, tokenHash)).first();
        return document != null ? ContextEntryDocuments.fromDocument(document) : null;
    }

    @Override
    protected void insert(final ContextEntryEntity entry) {
        collection.insertOne(ContextEntryDocuments.toDocument(entry));
    }

    @Override
    protected void updateReplacement(final String userId, final String contextName, final String tokenHash,
                                     final String replacement, final String filterType, final boolean replacementUuid) {
        collection.updateOne(byTokenHash(userId, contextName, tokenHash), Updates.combine(
                Updates.set("replacement", replacement),
                Updates.set("filter_type", filterType),
                Updates.set("replacement_uuid", replacementUuid)));
    }

    @Override
    protected ContextEntryEntity deleteById(final String id, final String userId, final String contextName) {
        final Bson owned = Filters.and(Filters.eq("_id", MongoIds.objectId(id)), Filters.eq("user_id", MongoIds.objectId(userId)));
        // findOneAndDelete rather than deleteOne: the removed document carries the context and token
        // hash the cache is keyed by, and reading them first would race the delete.
        final Document deleted = collection.findOneAndDelete(contextName == null ? owned
                : Filters.and(owned, Filters.eq("context_name", contextName)));
        return deleted == null ? null : ContextEntryDocuments.fromDocument(deleted);
    }

    @Override
    protected ContextEntryEntity findLeastRead(final String userId, final String contextName) {
        final Document victim = collection.find(inContext(userId, contextName))
                .sort(Sorts.orderBy(Sorts.ascending("reads"), Sorts.ascending("timestamp")))
                .first();
        return victim == null ? null : ContextEntryDocuments.fromDocument(victim);
    }

    @Override
    protected void deleteContext(final String userId, final String contextName) {
        collection.deleteMany(inContext(userId, contextName));
    }

    @Override
    public void incrementReads(final String id) {
        collection.updateOne(Filters.eq("_id", MongoIds.objectId(id)), Updates.inc("reads", 1L));
    }

    @Override
    public List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, final int offset,
                                                              final int limit, final Listings.Sort sort) {
        final List<ContextEntryEntity> entries = new ArrayList<>();
        for (final Document document : collection.find(inContext(userId, contextName))
                .sort(MongoListings.sort(sort))
                .skip(Math.max(0, offset))
                .limit(Math.min(limit, MAX_LIMIT))) {
            entries.add(ContextEntryDocuments.fromDocument(document));
        }
        return entries;
    }

    @Override
    public int countByUserIdAndContext(final String userId, final String contextName) {
        return (int) collection.countDocuments(inContext(userId, contextName));
    }

    @Override
    public List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName) {
        final List<ContextEntryEntity> entries = new ArrayList<>();
        for (final Document document : collection.find(inContext(userId, contextName)).sort(Sorts.descending("timestamp"))) {
            entries.add(ContextEntryDocuments.fromDocument(document));
        }
        return entries;
    }

    @Override
    public boolean containsReplacement(final String userId, final String contextName, final String replacement) {
        // Replacement is not encrypted because it is not PII/PHI.
        return collection.find(Filters.and(inContext(userId, contextName), Filters.eq("replacement", replacement))).first() != null;
    }

    @Override
    public Map<String, Long> getFilterTypeCounts(final String contextName, final String userId) {

        final List<Document> pipeline = List.of(
                new Document("$match", new Document("context_name", contextName).append("user_id", MongoIds.objectId(userId))),
                new Document("$group", new Document("_id", "$filter_type").append("count", new Document("$sum", 1))));

        final Map<String, Long> filterTypeCounts = new HashMap<>();

        for (final Document document : collection.aggregate(pipeline)) {
            // $sum yields an int or, past its range, a long.
            final Object count = document.get("count");
            if (count instanceof Number number) {
                filterTypeCounts.merge(document.getString("_id"), number.longValue(), Long::sum);
            }
        }

        return filterTypeCounts;

    }

}
