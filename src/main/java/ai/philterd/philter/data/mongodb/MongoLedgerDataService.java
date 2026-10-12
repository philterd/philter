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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.EvidenceGuard;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredLedgerDataService;
import ai.philterd.philter.data.services.UnreadableLedgerEntryException;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;
import com.mongodb.MongoException;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.CountOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Stores the redaction ledger in MongoDB: a document per entry and a document per chain holding its state. */
public class MongoLedgerDataService extends StoredLedgerDataService {

    private final EncryptionService encryptionService;
    private final MongoCollection<Document> collection;
    private final MongoCollection<Document> evidence;
    private final MongoCollection<Document> chainStates;

    public MongoLedgerDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                  final AuditEventPublisher auditEventPublisher,
                                  final LegalHoldDataService legalHoldDataService,
                                  final SigningService signingService) {
        this(mongoClient, encryptionService, auditEventPublisher, legalHoldDataService, signingService,
                new MongoEvidenceGuard(mongoClient));
    }

    public MongoLedgerDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                  final AuditEventPublisher auditEventPublisher,
                                  final LegalHoldDataService legalHoldDataService,
                                  final SigningService signingService, final EvidenceGuard evidenceGuard) {
        super(auditEventPublisher, legalHoldDataService, signingService, evidenceGuard);
        this.encryptionService = encryptionService;
        final MongoRecords<LedgerEntity> records = new MongoRecords<>(mongoClient, "ledger",
                entity -> LedgerDocuments.toDocument(entity, encryptionService));
        this.collection = records.collection;
        this.evidence = collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        this.chainStates = mongoClient.getDatabase("philter").getCollection("ledger_chains")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        chainStates.createIndex(Indexes.ascending("user_id", "state", "completed_at"));

        // Chain-head listing queries (user_id, previous_hash) ordered by timestamp; per-document
        // chain retrieval and deletion query (user_id, document_id).
        records.ensureIndex(Indexes.ascending("user_id", "previous_hash", "timestamp"));
        records.ensureIndex(Indexes.ascending("user_id", "document_id", "timestamp"));
        // Chain order, and the guarantee that a chain has one entry per position.
        ensureUniqueChainPositions(collection);
        // Chain heads across users (previous_hash) newest first, with _id as the paging tie-breaker. The
        // indexes above begin with user_id, so a query across users cannot use them.
        records.ensureIndex(Indexes.compoundIndex(Indexes.ascending("previous_hash"), Indexes.descending("timestamp", "_id")));

        RequiredSchema.rejectAutomaticExpiry(collection);
    }

    /**
     * A chain has one entry per position, so two writers can never both append at one place. A failure to create the
     * index stops Philter: running without it looks the same and would let a chain fork.
     */
    private static void ensureUniqueChainPositions(final MongoCollection<Document> collection) {
        try {
            collection.createIndex(Indexes.ascending("user_id", "document_id", "chain_position"),
                    new IndexOptions().unique(true));
        } catch (final Exception ex) {
            throw new IllegalStateException("Unable to create the unique chain position index on 'ledger'.", ex);
        }
    }

    private static String chainId(final String userId, final String documentId) {
        return userId + ":" + documentId;
    }

    private static String stored(final ChainState state) {
        return state.name().toLowerCase(Locale.ROOT);
    }

    private static Bson chain(final String userId, final String documentId) {
        return Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)), Filters.eq("document_id", documentId));
    }

    @Override
    protected boolean beginWriting(final String userId, final String documentId) {
        final String chainId = chainId(userId, documentId);
        chainStates.updateOne(Filters.eq("_id", chainId),
                new Document("$setOnInsert", new Document("user_id", MongoIds.objectId(userId))
                        .append("document_id", documentId).append("state", stored(ChainState.OPEN))),
                new UpdateOptions().upsert(true));
        return chainStates.updateOne(Filters.and(Filters.eq("_id", chainId), Filters.eq("state", stored(ChainState.OPEN))),
                Updates.set("state", stored(ChainState.WRITING))).getModifiedCount() == 1;
    }

    @Override
    protected void endWriting(final String userId, final String documentId, final Date entryTimestamp) {
        chainStates.updateOne(Filters.eq("_id", chainId(userId, documentId)), Updates.combine(
                Updates.max("latest_entry_at", entryTimestamp),
                Updates.set("state", stored(ChainState.OPEN))));
    }

    @Override
    protected void markWriteFailed(final String userId, final String documentId) {
        chainStates.updateOne(Filters.eq("_id", chainId(userId, documentId)), Updates.set("state", stored(ChainState.FAILED)));
    }

    @Override
    protected void insertEntry(final LedgerEntity entry) {
        collection.insertOne(LedgerDocuments.toDocument(entry, encryptionService));
    }

    @Override
    protected boolean complete(final String userId, final String documentId, final Date now) {
        return chainStates.updateOne(Filters.and(Filters.eq("_id", chainId(userId, documentId)),
                        Filters.eq("state", stored(ChainState.OPEN))),
                Updates.combine(Updates.set("state", stored(ChainState.COMPLETE)), Updates.set("completed_at", now)))
                .getModifiedCount() == 1;
    }

    @Override
    protected ChainState chainState(final String userId, final String documentId) {
        final Document chain = chainStates.find(Filters.eq("_id", chainId(userId, documentId))).first();
        return chain == null ? null : ChainState.valueOf(chain.getString("state").toUpperCase(Locale.ROOT));
    }

    @Override
    protected boolean markPurged(final String userId, final String documentId) {
        return chainStates.updateOne(Filters.and(Filters.eq("_id", chainId(userId, documentId)),
                        Filters.in("state", stored(ChainState.COMPLETE), stored(ChainState.PURGED))),
                Updates.set("state", stored(ChainState.PURGED))).getMatchedCount() == 1;
    }

    @Override
    protected long deleteEntries(final String userId, final String documentId) {
        return evidence.deleteMany(chain(userId, documentId)).getDeletedCount();
    }

    @Override
    protected List<String> findCompletedBefore(final String userId, final Date cutoff) {
        final List<String> documentIds = new ArrayList<>();
        for (final Document chain : chainStates.find(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("state", stored(ChainState.COMPLETE)), Filters.lt("completed_at", cutoff),
                Filters.lt("latest_entry_at", cutoff)))) {
            documentIds.add(chain.getString("document_id"));
        }
        return documentIds;
    }

    @Override
    protected Listings.Page<LedgerEntity> findChainHeads(final String userId, final String q, final Listings.Sort sort,
                                                         final int offset, final int limit) {
        final Bson filter = MongoListings.all(userId == null ? null : Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("previous_hash", GENESIS),
                q == null ? null : Filters.or(MongoListings.contains("document_id", q), MongoListings.contains("filename", q)));
        return MongoListings.page(collection, filter, sort, offset, limit,
                document -> LedgerDocuments.fromDocumentOrUnreadable(document, encryptionService));
    }

    @Override
    public boolean chainExists(final String userId, final String documentId) {
        return collection.countDocuments(chain(userId, documentId), new CountOptions().limit(1)) > 0;
    }

    @Override
    public List<LedgerEntity> getChain(final String userId, final String documentId) {

        // In chain order. Neither timestamps nor ids give it: entries written in a tight loop share a
        // millisecond, and ObjectIds made in one second on different instances do not sort in write order.
        final List<LedgerEntity> ledgerEntries = new ArrayList<>();

        for (final Document document : collection.find(chain(userId, documentId)).sort(Sorts.ascending("chain_position"))) {
            try {
                ledgerEntries.add(LedgerDocuments.fromDocument(document, encryptionService));
            } catch (final MongoException e) {
                throw e;
            } catch (final RuntimeException e) {
                throw new UnreadableLedgerEntryException(documentId, e);
            }
        }

        return Collections.unmodifiableList(ledgerEntries);

    }

    @Override
    public LedgerEntity getLatestTransaction(final String userId, final String documentId) {
        // The latest entry is the one at the highest position, matching getChain's order. Read from the primary:
        // an append checks it to decide the next position and refuse a stale entry, so it must not lag.
        final Document document = evidence.find(chain(userId, documentId)).sort(Sorts.descending("chain_position")).first();
        return document == null ? null : LedgerDocuments.fromDocument(document, encryptionService);
    }

}
