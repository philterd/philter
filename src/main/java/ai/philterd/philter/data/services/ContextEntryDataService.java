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

import ai.philterd.philter.services.encryption.ContextTokenHasher;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.utils.EnvUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;

public class ContextEntryDataService extends AbstractService<ContextEntryEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextEntryDataService.class);

    public static final int MAX_LIMIT = 100;

    public static final int MAX_CONTEXT_SIZE =
            EnvUtils.getInt("MAX_CONTEXT_SIZE", ContextEntity.MAX_CONTEXT_SIZE);

    private static final Pattern UUID_REGEX_PATTERN = Pattern.compile(
            "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$"
    );

    public ContextEntryDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "context_entries", auditEventPublisher);

        // Token lookups during redaction hit (user_id, context_name, token_hash); listing/eviction
        // scans (user_id, context_name) ordered by timestamp/reads.
        ensureUniqueTokenIndex();
        ensureIndex(Indexes.ascending("user_id", "context_name", "timestamp"));
    }

    public void incrementReads(final ObjectId id) {
        collection.updateOne(Filters.eq("_id", id), Updates.inc("reads", 1L));
    }

    public List<ContextEntryEntity> findAllByUserIdAndContext(final ObjectId userId, final String contextName, int limit) {
        return findAllByUserIdAndContext(userId, contextName, 0, limit);
    }

    public List<ContextEntryEntity> findAllByUserIdAndContext(final ObjectId userId, final String contextName, int offset, int limit) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        final Document query = new Document("user_id", userId).append("context_name", contextName);

        final Iterable<Document> documents = collection.find(query)
                .sort(Sorts.descending("timestamp"))
                .skip(Math.max(0, offset))
                .limit(effectiveLimit);

        final List<ContextEntryEntity> contextEntities = new ArrayList<>();

        for(final Document document : documents) {
            contextEntities.add(ContextEntryEntity.fromDocument(document));
        }

        return contextEntities;

    }

    public int countByUserIdAndContext(final ObjectId userId, final String contextName) {
        return (int) collection.countDocuments(new Document("user_id", userId).append("context_name", contextName));
    }

    /**
     * Returns every entry in the context for the given user, without the paging cap applied by
     * {@link #findAllByUserIdAndContext}. Intended for exporting an entire mapping table. A context
     * is bounded by {@link #MAX_CONTEXT_SIZE}, so the result set is bounded too.
     */
    public List<ContextEntryEntity> findAllByUserIdAndContext(final ObjectId userId, final String contextName) {

        final Document query = new Document("user_id", userId).append("context_name", contextName);

        final List<ContextEntryEntity> entries = new ArrayList<>();
        for (final Document document : collection.find(query).sort(Sorts.descending("timestamp"))) {
            entries.add(ContextEntryEntity.fromDocument(document));
        }

        return entries;

    }

    /** The outcome of importing a single mapping into a context. */
    public enum ImportOutcome { INSERTED, OVERWRITTEN, SKIPPED }

    /**
     * Imports a single token-to-replacement mapping keyed by an already-computed token hash (the
     * original token is never available on import). If the token hash already exists in the context,
     * it is left untouched when {@code overwrite} is {@code false} (returning {@link
     * ImportOutcome#SKIPPED}) or updated when {@code overwrite} is {@code true} (returning {@link
     * ImportOutcome#OVERWRITTEN}). A new mapping is inserted otherwise, honoring {@link
     * #MAX_CONTEXT_SIZE} eviction, and returns {@link ImportOutcome#INSERTED}.
     */
    public ImportOutcome importEntryByHash(final ObjectId userId, final String contextName, final String tokenHash,
                                           final String replacement, final String filterType, final boolean replacementUuid,
                                           final boolean overwrite) {

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("context_name", contextName),
                Filters.eq("token_hash", tokenHash));

        final Document existing = collection.find(query).first();

        if (existing != null) {

            if (!overwrite) {
                return ImportOutcome.SKIPPED;
            }

            collection.updateOne(query, Updates.combine(
                    Updates.set("replacement", replacement),
                    Updates.set("filter_type", filterType),
                    Updates.set("replacement_uuid", replacementUuid)));

            return ImportOutcome.OVERWRITTEN;

        }

        evictIfFull(userId, contextName);

        final ContextEntryEntity contextEntryEntity = new ContextEntryEntity();
        contextEntryEntity.setUserId(userId);
        contextEntryEntity.setContextName(contextName);
        contextEntryEntity.setTokenHash(tokenHash);
        contextEntryEntity.setReplacement(replacement);
        contextEntryEntity.setReads(0L);
        contextEntryEntity.setTimestamp(new Date());
        contextEntryEntity.setFilterType(filterType);
        contextEntryEntity.setReplacementUuid(replacementUuid);

        collection.insertOne(contextEntryEntity.toDocument());

        return ImportOutcome.INSERTED;

    }

    public long deleteByIdAndUserId(final ObjectId id, final ObjectId userId) {
        return collection.deleteOne(Filters.and(Filters.eq("_id", id), Filters.eq("user_id", userId))).getDeletedCount();
    }

    public boolean containsToken(final ObjectId userId, final String contextName, final String token) {

        // The token must be hashed.
        final String tokenHash = ContextTokenHasher.hash(token);

        final Document query = new Document("user_id", userId).append("context_name", contextName).append("token_hash", tokenHash);
        final Document document = collection.find(query).first();
        return document != null;

    }

    public boolean containsReplacement(final ObjectId userId, final String contextName, final String replacement) {

        // Replacement is not encrypted because it is not PII/PHI.

        final Document query = new Document("user_id", userId).append("context_name", contextName).append("replacement", replacement);
        final Document document = collection.find(query).first();
        return document != null;

    }

    public String getReplacement(final ObjectId userId, final String contextName, final String token) {
        final ContextEntryEntity entry = findOneEntryByToken(userId, contextName, token);
        if (entry == null) {
            return null;
        }
        incrementReads(entry.getId());
        return entry.getReplacement();
    }

    public ContextEntryEntity findOneEntryByToken(final ObjectId userId, final String contextName, final String token) {

        // The token must be hashed.
        final String tokenHash = ContextTokenHasher.hash(token);

        final Document query = new Document("user_id", userId).append("context_name", contextName).append("token_hash", tokenHash);
        final Document document = collection.find(query).first();

        return document != null ? ContextEntryEntity.fromDocument(document) : null;

    }

    @Override
    public ObjectId save(final ContextEntryEntity contextEntryEntity) {
        throw new UnsupportedOperationException("Use putReplacement() instead.");
    }

    public void putReplacement(final ObjectId userId, final String contextName, final String token, final String replacement, final String filterType) {
        putReplacementIfAbsent(userId, contextName, token, replacement, filterType);
    }

    /**
     * Records the replacement for a token unless one is already stored, and returns whichever is
     * stored afterwards. Two documents redacted at once must agree on a pseudonym, so the caller has
     * to use the returned value rather than the one it proposed: reading, deciding and inserting
     * separately let both callers decide "absent" and both insert.
     */
    public ContextEntryEntity putReplacementIfAbsent(final ObjectId userId, final String contextName,
                                                     final String token, final String replacement,
                                                     final String filterType) {

        final String tokenHash = ContextTokenHasher.hash(token);
        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("context_name", contextName),
                Filters.eq("token_hash", tokenHash));

        final Bson insert = Updates.combine(
                Updates.setOnInsert("user_id", userId),
                Updates.setOnInsert("context_name", contextName),
                Updates.setOnInsert("token_hash", tokenHash),
                Updates.setOnInsert("replacement", replacement),
                Updates.setOnInsert("reads", 0L),
                Updates.setOnInsert("timestamp", new Date()),
                Updates.setOnInsert("filter_type", filterType),
                Updates.setOnInsert("replacement_uuid", UUID_REGEX_PATTERN.matcher(replacement).matches()));

        final FindOneAndUpdateOptions options = new FindOneAndUpdateOptions()
                .upsert(true)
                .returnDocument(ReturnDocument.AFTER);

        final Document stored;
        try {
            stored = collection.findOneAndUpdate(query, insert, options);
        } catch (final MongoWriteException | MongoCommandException lostTheRace) {
            // The unique index rejected a second insert; the winner's row is what counts.
            final Document winner = collection.find(query).first();
            return winner != null ? ContextEntryEntity.fromDocument(winner) : null;
        }

        if (stored != null && replacement.equals(stored.getString("replacement"))) {
            // This call is the one that created the entry, so it is the one that may have filled the
            // context. Trimmed afterwards rather than before: an upsert that changes nothing must not
            // evict anything.
            evictIfFull(userId, contextName);
        }

        return stored != null ? ContextEntryEntity.fromDocument(stored) : null;

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

    private void evictIfFull(final ObjectId userId, final String contextName) {

        final Bson contextFilter = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("context_name", contextName)
        );

        final long currentSize = collection.countDocuments(contextFilter);
        if (currentSize < MAX_CONTEXT_SIZE) {
            return;
        }

        final Document victim = collection.find(contextFilter)
                .sort(Sorts.orderBy(Sorts.ascending("reads"), Sorts.ascending("timestamp")))
                .first();

        if (victim != null) {
            collection.deleteOne(Filters.eq("_id", victim.getObjectId("_id")));
            LOGGER.info("Evicted context entry {} from context {} (reads={}) to honor MAX_CONTEXT_SIZE={}",
                    victim.getObjectId("_id"), contextName, victim.getLong("reads"), MAX_CONTEXT_SIZE);
        }

    }

    public void deleteByContextName(final String contextName, final ObjectId userId) {

        final Document filter = new Document("context_name", contextName).append("user_id", userId);
        collection.deleteMany(filter);

    }

    public Map<String, Long> getFilterTypeCounts(final String contextName, final ObjectId userId) {

        final Document matchStage = new Document("$match",
                new Document("context_name", contextName)
                        .append("user_id", userId)
                        .append("replacement_uuid", false));

        final Document groupStage = new Document("$group",
                new Document("_id", "$filter_type")
                        .append("count", new Document("$sum", 1)));

        final List<Document> pipeline = new ArrayList<>();
        pipeline.add(matchStage);
        pipeline.add(groupStage);

        final Map<String, Long> filterTypeCounts = new HashMap<>();

        for (final Document document : collection.aggregate(pipeline)) {
            final String filterType = document.getString("_id");
            final Integer count = document.getInteger("count");
            if (filterType != null && count != null) {
                filterTypeCounts.put(filterType, count.longValue());
            }
        }

        return filterTypeCounts;

    }

}