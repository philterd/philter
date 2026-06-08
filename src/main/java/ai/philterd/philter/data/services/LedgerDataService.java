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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.utils.EnvUtils;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.DeleteResult;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class LedgerDataService extends AbstractEncryptedService<LedgerEntity> {

    private static final Logger LOGGER = LogManager.getLogger(LedgerDataService.class);

    public static final String GENESIS = "[genesis]";
    public static final String EMPTY_ENTRY = "";
    public static final int MAX_LIMIT = 100;

    // Ledger retention. By default ledger entries are kept indefinitely (the ledger is a
    // tamper-evident audit record, so nothing is auto-deleted unless an operator opts in).
    // Set REDACTION_LEDGER_TTL_SECONDS to a positive value to have MongoDB automatically expire
    // entries older than that; entries can also be removed explicitly via the manual purge
    // (deleteChainsByUserIdAndOlderThan) and per-document/user deletions.
    private static final long DEFAULT_TTL_SECONDS = 0L;

    // The auto-generated name of the optional TTL index on the entry timestamp.
    private static final String TTL_INDEX_NAME = "timestamp_1";

    public LedgerDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "ledger", encryptionService, auditEventPublisher);

        // Chain-head listing queries (user_id, previous_hash) ordered by timestamp; per-document
        // chain retrieval and deletion query (user_id, document_id).
        ensureIndex(Indexes.ascending("user_id", "previous_hash", "timestamp"));
        ensureIndex(Indexes.ascending("user_id", "document_id", "timestamp"));

        // Optional TTL: when a positive retention is configured, expire ledger entries older than
        // it. The index is on the entry timestamp. Changing the value after the index exists
        // requires dropping the existing TTL index first (MongoDB will not silently re-apply a
        // different expireAfterSeconds), so a conflict is logged rather than fatal at startup.
        final long ttlSeconds = EnvUtils.getLong("REDACTION_LEDGER_TTL_SECONDS", DEFAULT_TTL_SECONDS);
        if (ttlSeconds > 0) {
            try {
                collection.createIndex(
                        Indexes.ascending("timestamp"),
                        new IndexOptions().expireAfter(ttlSeconds, TimeUnit.SECONDS));
                LOGGER.info("TTL index on ledger.timestamp set to expire after {} seconds.", ttlSeconds);
            } catch (final Exception ex) {
                LOGGER.warn("Unable to create TTL index on ledger.timestamp ({} seconds): {}", ttlSeconds, ex.getMessage());
            }
        } else {
            // Retention disabled (the default): keep entries indefinitely. Drop any TTL index left by a
            // previous deployment that configured retention, so the new "keep indefinitely" policy
            // actually takes effect on upgrade rather than entries continuing to silently expire.
            try {
                collection.dropIndex(TTL_INDEX_NAME);
                LOGGER.info("Dropped existing ledger TTL index '{}'; ledger retention is now unlimited.", TTL_INDEX_NAME);
            } catch (final Exception ex) {
                // No TTL index to drop (the common case) — nothing to do.
                LOGGER.debug("No ledger TTL index '{}' to drop: {}", TTL_INDEX_NAME, ex.getMessage());
            }
            LOGGER.info("Ledger retention is unlimited (REDACTION_LEDGER_TTL_SECONDS not set to a positive value).");
        }
    }

    public void initializeLedger(final ObjectId userId, final String documentId, final String inputDocumentHash, final String filename) throws Exception {
        addTransaction(new LedgerEntity(userId, documentId, EMPTY_ENTRY, EMPTY_ENTRY, 0, inputDocumentHash, GENESIS, filename, "", encryptionService));
    }

    public void addTransaction(final LedgerEntity ledgerEntity) {
        collection.insertOne(ledgerEntity.toDocument(encryptionService));
    }

    public boolean isChainValid(final ObjectId userId, final String documentId) throws Exception {

        final List<LedgerEntity> chain = getChain(userId, documentId);

        if(!chain.isEmpty()) {

            for (int i = 1; i < chain.size(); i++) {

                final LedgerEntity currentRedaction = chain.get(i);
                final LedgerEntity previousRedaction = chain.get(i - 1);

                if (!currentRedaction.getHash().equals(currentRedaction.calculateHash())) {
                    LOGGER.debug("Current hash is invalid for redaction of: {}", currentRedaction.getToken());
                    LOGGER.debug("Expected: {}", currentRedaction.getHash());
                    LOGGER.debug("Actual: {}", currentRedaction.calculateHash());
                    return false;
                }

                if (!currentRedaction.getPreviousHash().equals(previousRedaction.getHash())) {
                    LOGGER.warn("Previous hash link is broken for redaction of: {}", currentRedaction.getToken());
                    return false;
                }

            }

            return true;

        } else {

            // Chain was not found.
            return false;

        }

    }

    public List<LedgerEntity> searchChainsByUserId(final String requestId, final ObjectId userId, final String searchTerm, final String source) {

        final Pattern pattern = Pattern.compile(".*" + Pattern.quote(searchTerm) + ".*", Pattern.CASE_INSENSITIVE);

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("previous_hash", GENESIS),
                Filters.or(
                        Filters.regex("document_id", pattern),
                        Filters.regex("filename", pattern)
                )
        );

        final FindIterable<Document> documents = collection.find(query).sort(Sorts.descending("timestamp"));

        final List<LedgerEntity> ledgerEntries = new ArrayList<>();

        for(final Document document : documents) {
            ledgerEntries.add(LedgerEntity.fromDocument(document, encryptionService));
        }

        // Audit the query, recording a hash of the search term rather than the term itself so the
        // audit log never contains the (potentially sensitive) text that was searched for.
        final String searchTermHash = DigestUtils.sha256Hex(searchTerm);
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_QUERY, userId, null, source, "searchTermHash: " + searchTermHash);

        return ledgerEntries;

    }

    public long deleteChainsByUserIdAndOlderThan(final String requestId, final ObjectId userId, final int daysToKeep) {

        // Subtract the days to keep from the current date.
        // Ledger entries having a timestamp less than (older than) this timestamp will be returned.
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -daysToKeep);
        final Date cutoffDate = cal.getTime();

        final Document query = new Document("user_id", userId).append("timestamp", new Document("$lt", cutoffDate));

        final DeleteResult deleteResult = collection.deleteMany(query);

        // Audit the retention-driven deletion of ledger chains.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId, null, null, "deletedCount: " + deleteResult.getDeletedCount() + ", daysToKeep: " + daysToKeep);

        return deleteResult.getDeletedCount();

    }

    public List<LedgerEntity> findChainsByUserId(final String requestId, final ObjectId userId, final int offset, final int limit, final String source) {
        return findChainsByUserId(requestId, userId, offset, limit, source, "timestamp", false);
    }

    public List<LedgerEntity> findChainsByUserId(final String requestId, final ObjectId userId, final int offset, final int limit, final String source, final String sortField, final boolean ascending) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("previous_hash", GENESIS)
        );

        final Bson sortCriteria = ascending ? Sorts.ascending(sortField) : Sorts.descending(sortField);

        final FindIterable<Document> documents = collection.find(query)
                .skip(offset)
                .limit(effectiveLimit)
                .sort(sortCriteria);

        final List<LedgerEntity> ledgerEntries = new ArrayList<>();

        for(final Document document : documents) {
            ledgerEntries.add(LedgerEntity.fromDocument(document, encryptionService));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_QUERY, userId, null, source);

        return ledgerEntries;

    }

    /**
     * Returns one page of chain heads (genesis entries) across every user, most recent first. Intended
     * for admin-only views; ordinary access must use the owner-scoped {@link #findChainsByUserId}.
     */
    public List<LedgerEntity> findAllChainHeadsAcrossUsers(final int offset, final int limit) {

        final Bson query = Filters.eq("previous_hash", GENESIS);

        final FindIterable<Document> documents = collection.find(query)
                .sort(Sorts.descending("timestamp"))
                .skip(offset)
                .limit(limit);

        final List<LedgerEntity> ledgerEntries = new ArrayList<>();

        for (final Document document : documents) {
            ledgerEntries.add(LedgerEntity.fromDocument(document, encryptionService));
        }

        return ledgerEntries;

    }

    /** Returns the total number of chain heads (one per document) across every user (for admin paging). */
    public int countAllChainHeads() {
        return (int) collection.countDocuments(Filters.eq("previous_hash", GENESIS));
    }

    public int countChainsByUserId(final ObjectId userId) {

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("previous_hash", GENESIS)
        );

        return (int) collection.countDocuments(query);

    }

    public List<LedgerEntity> getChain(final ObjectId userId, final String documentId) {

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("document_id", documentId)
        );

        // Get the oldest items first to show the chain in order.
        final Bson sortCriteria = Sorts.ascending("timestamp");

        final FindIterable<Document> documents = collection.find(query).sort(sortCriteria);

        final List<LedgerEntity> ledgerEntries = new ArrayList<>();

        for(final Document document : documents) {
            ledgerEntries.add(LedgerEntity.fromDocument(document, encryptionService));
        }

        return Collections.unmodifiableList(ledgerEntries);

    }

    public LedgerEntity getLatestTransaction(final ObjectId userId, final String documentId) {

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("document_id", documentId)
        );

        final Bson sortCriteria = Sorts.descending("timestamp");

        final Document document = collection.find(query).sort(sortCriteria).first();

        if(document != null) {
            return LedgerEntity.fromDocument(document, encryptionService);
        } else {
            return null;
        }

    }

    public boolean isDocumentIdUnique(final ObjectId userId, final String documentId) {

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("document_id", documentId)
        );

        final Bson sortCriteria = Sorts.descending("timestamp");

        final Document document = collection.find(query).sort(sortCriteria).first();

        return document == null;

    }

    public ServiceResponse deleteByDocumentId(final String requestId, final ObjectId userId, final String documentId, final String source) {

        final Bson query = Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("document_id", documentId)
        );

        collection.deleteMany(query);
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId, null, source, "documentId: " + documentId);

        return ServiceResponse.success();

    }

    public long deleteAllByUserId(final ObjectId userId) {

        final Document query = new Document("user_id", userId);

        final DeleteResult deleteResult = collection.deleteMany(query);

        return deleteResult.getDeletedCount();

    }

}
