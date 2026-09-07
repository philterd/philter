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
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.signing.SigningService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.Filters;
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
import java.util.Objects;
import java.util.regex.Pattern;

public class LedgerDataService extends AbstractEncryptedService<LedgerEntity> {

    private static final Logger LOGGER = LogManager.getLogger(LedgerDataService.class);

    public static final String GENESIS = "[genesis]";
    public static final String EMPTY_ENTRY = "";
    public static final int MAX_LIMIT = 100;

    // The ledger is never expired automatically: it is governance evidence, so every deletion is a
    // deliberate, admin-only, hold-checked, audited act. Retention is enforced by scheduling the
    // purge endpoint, which has all of those properties; a MongoDB TTL index has none of them.
    private final LegalHoldDataService legalHoldDataService;
    private final SigningService signingService;
    private final MongoCollection<Document> chainStates;
    private final EvidenceOperationGuard evidenceGuard;
    private final MongoCollection<Document> evidence;

    public LedgerDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                              final AuditEventPublisher auditEventPublisher,
                              final LegalHoldDataService legalHoldDataService,
                              final SigningService signingService) {
        super(mongoClient, "ledger", encryptionService, auditEventPublisher);
        this.evidenceGuard = new EvidenceOperationGuard(mongoClient);
        this.evidence = collection.withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        this.chainStates = mongoClient.getDatabase("philter").getCollection("ledger_chains")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        chainStates.createIndex(Indexes.ascending("user_id", "state", "completed_at"));
        this.legalHoldDataService = legalHoldDataService;
        this.signingService = Objects.requireNonNull(signingService, "Ledger signing service is required.");

        // Chain-head listing queries (user_id, previous_hash) ordered by timestamp; per-document
        // chain retrieval and deletion query (user_id, document_id).
        ensureIndex(Indexes.ascending("user_id", "previous_hash", "timestamp"));
        ensureIndex(Indexes.ascending("user_id", "document_id", "timestamp"));

        RequiredSchema.rejectAutomaticExpiry(collection);

    }

    public void initializeLedger(final ObjectId userId, final String documentId, final String inputDocumentHash, final String filename,
                                 final String policyName, final int policyVersion, final String policyContentHash) throws Exception {
        addTransaction(new LedgerEntity(userId, documentId, EMPTY_ENTRY, EMPTY_ENTRY, 0, inputDocumentHash, GENESIS, filename, "",
                policyName, policyVersion, policyContentHash));
    }

    public void initializeLedger(final ObjectId userId, final String documentId, final String inputDocumentHash, final String filename,
                                 final String policyName, final int policyVersion, final String policyContentHash, final String effectiveHash) throws Exception {
        final LedgerEntity entry = new LedgerEntity(userId, documentId, EMPTY_ENTRY, EMPTY_ENTRY, 0, inputDocumentHash, GENESIS, filename, "",
                policyName, policyVersion, policyContentHash);
        entry.setEffectiveHash(effectiveHash);
        entry.setHash(entry.calculateHash());
        addTransaction(entry);
    }

    public void addTransaction(final LedgerEntity ledgerEntity) {
        save(ledgerEntity);
    }

    /** Every insert must be signed, including callers of the inherited persistence API. */
    @Override
    public ObjectId save(final LedgerEntity ledgerEntity) {
        if (ledgerEntity.getHash() == null || ledgerEntity.getHash().isBlank()) {
            throw new IllegalArgumentException("A ledger entry must have a hash before signing.");
        }
        final SigningService.LedgerSignature signed;
        try {
            signed = signingService.signLedgerEntry(ledgerEntity.getHash());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to sign ledger entry; it was not saved.", e);
        }
        if (signed == null || signed.signature() == null || signed.signature().isBlank()
                || signed.keyId() == null || signed.keyId().isBlank()) {
            throw new IllegalStateException("Ledger signing returned no signature or key ID; entry was not saved.");
        }
        ledgerEntity.setSignature(signed.signature());
        ledgerEntity.setSigningKeyId(signed.keyId());
        final String chainId = chainId(ledgerEntity.getUserId(), ledgerEntity.getDocumentId());
        chainStates.updateOne(Filters.eq("_id", chainId),
                new Document("$setOnInsert", new Document("user_id", ledgerEntity.getUserId())
                        .append("document_id", ledgerEntity.getDocumentId()).append("state", "open")),
                new UpdateOptions().upsert(true));
        // A database CAS fences sealing/purging against appends across all instances.
        if (chainStates.updateOne(Filters.and(Filters.eq("_id", chainId), Filters.eq("state", "open")),
                Updates.set("state", "writing")).getModifiedCount() != 1) {
            throw new IllegalStateException("Ledger chain is not open for writing.");
        }
        try {
            final ObjectId id = super.save(ledgerEntity);
            chainStates.updateOne(Filters.eq("_id", chainId), Updates.combine(
                    Updates.max("latest_entry_at", ledgerEntity.getTimestamp()),
                    Updates.set("state", "open")));
            return id;
        } catch (RuntimeException ex) {
            // An uncertain/failed insert cannot turn into a completed, purgeable chain.
            chainStates.updateOne(Filters.eq("_id", chainId), Updates.set("state", "failed"));
            throw ex;
        }
    }

    private static String chainId(final ObjectId userId, final String documentId) {
        if (userId == null || documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("A ledger chain requires an owner and document ID.");
        }
        return userId.toHexString() + ":" + documentId;
    }

    /** Seals successfully recorded evidence. Further appends to this document ID are rejected. */
    public void completeChain(final ObjectId userId, final String documentId) {
        if (chainStates.updateOne(Filters.and(Filters.eq("_id", chainId(userId, documentId)),
                        Filters.eq("state", "open")),
                Updates.combine(Updates.set("state", "complete"), Updates.set("completed_at", new Date())))
                .getModifiedCount() != 1) {
            throw new IllegalStateException("Ledger chain could not be completed.");
        }
    }

    /** Evidence is append-only; an inherited update must not bypass signed insertion. */
    @Override
    public void update(final LedgerEntity ledgerEntity) {
        throw new UnsupportedOperationException("Ledger entries cannot be updated.");
    }

    /**
     * Hash-chain consistency and signature authenticity are separate checks. An unsigned entry is
     * unauthenticated and makes the chain invalid, even when every hash and link is consistent.
     */
    public record ChainValidation(boolean hashChainValid, boolean signaturesValid,
                                  int signedEntries, int unsignedEntries) {
        public boolean valid() {
            return hashChainValid && signaturesValid && unsignedEntries == 0;
        }
    }

    public boolean isChainValid(final ObjectId userId, final String documentId) throws Exception {
        return validateChain(userId, documentId).valid();
    }

    public ChainValidation validateChain(final ObjectId userId, final String documentId) throws Exception {

        final List<LedgerEntity> chain = getChain(userId, documentId);

        if (chain.isEmpty()) {
            return new ChainValidation(false, false, 0, 0);
        }

        final LedgerEntity genesis = chain.get(0);

        // The head must be a genesis entry whose own hash recomputes; the link checks below skip it.
        boolean hashChainValid = GENESIS.equals(genesis.getPreviousHash())
                && Objects.equals(genesis.getHash(), genesis.calculateHash());

        if (!hashChainValid) {
            LOGGER.warn("The genesis entry for document {} is missing or does not verify.", documentId);
        }

        for (int i = 1; hashChainValid && i < chain.size(); i++) {

            final LedgerEntity currentRedaction = chain.get(i);
            final LedgerEntity previousRedaction = chain.get(i - 1);

            // Identify the entry, never its token: that is the decrypted PII.
            if (!Objects.equals(currentRedaction.getHash(), currentRedaction.calculateHash())) {
                LOGGER.warn("Ledger entry {} (document {}, position {}) does not match its recomputed hash.",
                        currentRedaction.getId(), documentId, i);
                hashChainValid = false;
                break;
            }

            if (!Objects.equals(currentRedaction.getPreviousHash(), previousRedaction.getHash())) {
                LOGGER.warn("Ledger entry {} (document {}, position {}) does not link to the previous entry.",
                        currentRedaction.getId(), documentId, i);
                hashChainValid = false;
                break;
            }

        }

        // The hash chain proves the entries are internally consistent. The signatures prove this
        // deployment produced them, which a rewritten-and-rehashed chain cannot fake.
        boolean signaturesValid = true;
        int signed = 0;
        int unsigned = 0;

        for (final LedgerEntity entry : chain) {
            if (entry.getSignature() == null || entry.getSignature().isBlank()) {
                unsigned++;
                signaturesValid = false;
                continue;
            }
            signed++;
            if (entry.getSigningKeyId() == null || entry.getSigningKeyId().isBlank()
                    || !signingService.verifyLedgerEntry(entry.getHash(), entry.getSignature(), entry.getSigningKeyId())) {
                LOGGER.warn("Ledger entry signature does not verify for document {}.", documentId);
                signaturesValid = false;
            }
        }

        return new ChainValidation(hashChainValid, signaturesValid, signed, unsigned);

    }

    /** Built in one place so a search and its count cannot describe different result sets. */
    private Bson searchQuery(final ObjectId userId, final String searchTerm) {

        final Pattern pattern = Pattern.compile(".*" + Pattern.quote(searchTerm) + ".*", Pattern.CASE_INSENSITIVE);

        return Filters.and(
                Filters.eq("user_id", userId),
                Filters.eq("previous_hash", GENESIS),
                Filters.or(
                        Filters.regex("document_id", pattern),
                        Filters.regex("filename", pattern)
                )
        );

    }

    /** Counts the chain heads a search matches. Does not audit; the search it accompanies does. */
    public int countChainsByUserIdMatching(final ObjectId userId, final String searchTerm) {
        return (int) collection.countDocuments(searchQuery(userId, searchTerm));
    }

    public List<LedgerEntity> searchChainsByUserId(final String requestId, final ObjectId userId,
            final String searchTerm, final int offset, final int limit, final String source) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);

        final FindIterable<Document> documents = collection.find(searchQuery(userId, searchTerm))
                .sort(Sorts.descending("timestamp"))
                .skip(offset)
                .limit(effectiveLimit);

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

    /**
     * Purges complete chains whose completion and newest entry precede the retention cutoff.
     * Open, writing, and failed chains are retained for review; manual deletion also requires a sealed chain.
     *
     * <p><strong>Hold enforcement:</strong> if any active legal hold exists for this user (whether
     * a {@code user}-scoped hold or any {@code document_chain} hold), the purge is blocked in its
     * entirety and a 423 {@link ServiceResponse} is returned. Age-based purges cannot selectively
     * skip protected documents, so the presence of any hold prevents the whole operation. The
     * blocked attempt is audited as {@code legal_hold_blocked_deletion}.
     *
     * @return a {@link ServiceResponse} carrying the deleted count in the message on success, or
     *         a 423 with the blocking hold references when a hold is in force.
     */
    public ServiceResponse deleteChainsByUserIdAndOlderThan(final String requestId,
                                                             final ObjectId userId,
                                                             final int daysToKeep) {

        if (daysToKeep < 0) {
            return new ServiceResponse("daysToKeep must be zero or greater.", false, 400);
        }
        return evidenceGuard.execute(userId, "purge_by_age", () -> {
            if (legalHoldDataService.hasAnyHold(userId)) {
                final List<LegalHoldEntity> holds = legalHoldDataService.findAllHoldsForUser(userId);
                final String refs = holds.stream().map(LegalHoldEntity::getReference)
                        .reduce((a, b) -> a + ", " + b).orElse("unknown");
                auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                        userId, null, null,
                        "operation: purge_by_age, daysToKeep: " + daysToKeep + ", blocking holds: " + refs);
                return new ServiceResponse(
                        "Purge blocked by legal hold(s): " + refs + ". Release all holds before purging.",
                        false, 423);
            }

            final Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -daysToKeep);
            final Date cutoffDate = cal.getTime();

            final Bson eligible = Filters.and(Filters.eq("user_id", userId), Filters.eq("state", "complete"),
                    Filters.lt("completed_at", cutoffDate), Filters.lt("latest_entry_at", cutoffDate));
            long deletedCount = 0;
            long deletedChains = 0;
            try (var cursor = chainStates.find(eligible).iterator()) {
                while (cursor.hasNext()) {
                    final Document chain = cursor.next();
                    // Sealed chains cannot acquire another writer. Delete by owner and document,
                    // never by individual entry timestamp. On failure the marker remains retryable.
                    deletedCount += evidence.deleteMany(Filters.and(Filters.eq("user_id", userId),
                            Filters.eq("document_id", chain.getString("document_id")))).getDeletedCount();
                    chainStates.updateOne(Filters.eq("_id", chain.getString("_id")), Updates.set("state", "purged"));
                    deletedChains++;
                }
            }

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId,
                    null, null, "deletedCount: " + deletedCount + ", deletedChains: " + deletedChains
                            + ", daysToKeep: " + daysToKeep);

            return new ServiceResponse("Deleted " + deletedCount + " ledger entries in " + deletedChains
                    + " completed chains older than " + daysToKeep + " days.", true, 200);
        });
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

        // Order by insertion order (_id is monotonic per Mongo instance), not timestamp: redaction
        // entries are written in a tight loop and can share a millisecond, so timestamp ordering is not
        // deterministic and would break the hash-chain links during validation.
        final Bson sortCriteria = Sorts.ascending("_id");

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

        // The latest entry is the most recently inserted (highest _id), matching getChain's ordering so
        // the previous-hash links are deterministic even when entries share a timestamp.
        final Bson sortCriteria = Sorts.descending("_id");

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

    /**
     * Deletes all ledger entries for the given document.
     *
     * <p><strong>Hold enforcement:</strong> returns 423 if any {@code document_chain} hold covers
     * this document ID, or if any {@code user}-scoped hold exists for the owner. The blocked
     * attempt is audited as {@code legal_hold_blocked_deletion}.
     */
    public ServiceResponse deleteByDocumentId(final String requestId, final ObjectId userId,
                                               final String documentId, final String source) {

        return evidenceGuard.execute(userId, "delete_document_chain", () -> {
            if (legalHoldDataService.isProtectedDocument(userId, documentId)) {
                final List<LegalHoldEntity> holds =
                        legalHoldDataService.findBlockingHoldsForDocument(userId, documentId);
                final String refs = holds.stream().map(LegalHoldEntity::getReference)
                        .reduce((a, b) -> a + ", " + b).orElse("unknown");
                auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                        userId, null, null,
                        "operation: delete_document_chain, documentId: " + documentId
                                + ", blocking holds: " + refs);
                return new ServiceResponse(
                        "Deletion blocked by legal hold(s): " + refs
                                + ". Release the hold(s) before deleting this chain.",
                        false, 423);
            }

            final Document chain = chainStates.find(Filters.eq("_id", chainId(userId, documentId))).first();
            if (chain == null) return new ServiceResponse("Ledger chain not found.", false, 404);
            if (!deletableChain(chain)) return publicationConflict();
            markDeleted(chain);
            final Bson query = Filters.and(
                    Filters.eq("user_id", userId),
                    Filters.eq("document_id", documentId));

            evidence.deleteMany(query);
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId,
                    null, source, "documentId: " + documentId);

            return ServiceResponse.success();
        });
    }

    /**
     * Deletes all ledger entries for the given user. Intended for administrative bulk removal.
     *
     * <p><strong>Hold enforcement:</strong> returns 423 if any active hold exists for this user.
     * The blocked attempt is audited as {@code legal_hold_blocked_deletion}.
     */
    public ServiceResponse deleteAllByUserId(final String requestId, final ObjectId userId) {

        return evidenceGuard.execute(userId, "delete_all_by_user", () -> {
            if (legalHoldDataService.hasAnyHold(userId)) {
                final List<LegalHoldEntity> holds = legalHoldDataService.findAllHoldsForUser(userId);
                final String refs = holds.stream().map(LegalHoldEntity::getReference)
                        .reduce((a, b) -> a + ", " + b).orElse("unknown");
                auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                        userId, null, null,
                        "operation: delete_all_by_user, blocking holds: " + refs);
                return new ServiceResponse(
                        "Deletion blocked by legal hold(s): " + refs
                                + ". Release all holds before deleting user evidence.",
                        false, 423);
            }

            // Freeze the deletion set. A chain created after this read must not be swept up
            // by an owner-wide delete while its genesis or subsequent entries are being written.
            final List<Document> chains = chainStates.find(Filters.eq("user_id", userId)).into(new ArrayList<>());
            if (chains.stream().anyMatch(chain -> !deletableChain(chain))) return publicationConflict();
            long deletedCount = 0;
            for (final Document chain : chains) {
                markDeleted(chain);
                deletedCount += evidence.deleteMany(Filters.and(Filters.eq("user_id", userId),
                        Filters.eq("document_id", chain.getString("document_id")))).getDeletedCount();
            }

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId,
                    null, null, "operation: delete_all_by_user, deletedCount: " + deletedCount);

            return new ServiceResponse("Deleted " + deletedCount + " ledger entries from the selected completed chains.",
                    true, 200);
        });
    }

    private static boolean deletableChain(final Document chain) {
        return "complete".equals(chain.getString("state")) || "purged".equals(chain.getString("state"));
    }

    private static ServiceResponse publicationConflict() {
        return new ServiceResponse("Ledger publication is active or requires recovery. Only completed chains can be deleted.",
                false, 409);
    }

    private void markDeleted(final Document chain) {
        // Preserve the tombstone even when deleting entries fails. Neither a delayed writer
        // nor a new genesis can reopen this document ID; retrying deletion remains safe.
        if (chainStates.updateOne(Filters.and(Filters.eq("_id", chain.getString("_id")),
                        Filters.in("state", "complete", "purged")), Updates.set("state", "purged"))
                .getMatchedCount() != 1) {
            throw new IllegalStateException("Ledger chain deletion state could not be established.");
        }
    }

}
