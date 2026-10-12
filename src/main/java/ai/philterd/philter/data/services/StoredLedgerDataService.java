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
import ai.philterd.philter.services.signing.SigningService;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * The rules for the redaction ledger, the same in every storage implementation: every entry is signed before it
 * is stored, a chain moves from open to complete and is then only ever purged, a chain is validated by its hash
 * links and signatures, deletion and purging take the owner's {@link EvidenceGuard} and are blocked by legal
 * holds, and queries and deletions are audited. An implementation stores the entries and the chains' states.
 *
 * <p>The ledger is never expired automatically: it is governance evidence, so every deletion is a deliberate,
 * admin-only, hold-checked, audited act.
 */
public abstract class StoredLedgerDataService implements LedgerDataService {

    private static final Logger LOGGER = LogManager.getLogger(LedgerDataService.class);

    public static final String EMPTY_ENTRY = "";

    /** A chain's state: written to while open, sealed once complete, and purged once its entries are deleted. */
    public enum ChainState { OPEN, WRITING, COMPLETE, FAILED, PURGED }

    protected final AuditEventPublisher auditEventPublisher;
    private final LegalHoldDataService legalHoldDataService;
    private final SigningService signingService;
    private final EvidenceGuard evidenceGuard;

    protected StoredLedgerDataService(final AuditEventPublisher auditEventPublisher,
                                      final LegalHoldDataService legalHoldDataService,
                                      final SigningService signingService, final EvidenceGuard evidenceGuard) {
        this.auditEventPublisher = auditEventPublisher;
        this.legalHoldDataService = legalHoldDataService;
        this.signingService = Objects.requireNonNull(signingService, "Ledger signing service is required.");
        this.evidenceGuard = evidenceGuard;
    }

    /**
     * Creates the chain open when it does not exist, then moves it from open to writing and returns true, or
     * returns false when it is not open. Of concurrent writers, sealers, and purgers, one wins.
     */
    protected abstract boolean beginWriting(final String userId, final String documentId);

    /** Moves a writing chain back to open, recording when its latest entry was made. */
    protected abstract void endWriting(final String userId, final String documentId, final Date entryTimestamp);

    /** Moves a chain whose write failed or may have failed to failed, so it can never be completed or purged. */
    protected abstract void markWriteFailed(final String userId, final String documentId);

    /** Stores a signed entry, its token and replacement encrypted. */
    protected abstract void insertEntry(final LedgerEntity entry);

    /** Moves an open chain to complete at {@code now}, and returns whether it did. */
    protected abstract boolean complete(final String userId, final String documentId, final Date now);

    /** The chain's state, or null when there is no chain. */
    protected abstract ChainState chainState(final String userId, final String documentId);

    /** Moves a complete or purged chain to purged, and returns whether it did. */
    protected abstract boolean markPurged(final String userId, final String documentId);

    /** Deletes every entry of the chain and returns how many. */
    protected abstract long deleteEntries(final String userId, final String documentId);

    /** The document ids of the owner's complete chains completed, and last written to, before {@code cutoff}. */
    protected abstract List<String> findCompletedBefore(final String userId, final Date cutoff);

    /**
     * A page of chain heads (genesis entries), the user's or every user's when it is null, whose document id or
     * file name contain {@code q}, and their total. A head that cannot be decrypted is listed, marked
     * unreadable.
     */
    protected abstract Listings.Page<LedgerEntity> findChainHeads(final String userId, final String q,
            final Listings.Sort sort, final int offset, final int limit);

    @Override
    public void initializeLedger(final String userId, final String documentId, final String inputDocumentHash, final String filename,
                                 final String policyName, final int policyVersion, final String policyContentHash) throws Exception {
        addTransaction(new LedgerEntity(userId, documentId, EMPTY_ENTRY, EMPTY_ENTRY, 0, inputDocumentHash, GENESIS, filename, "",
                policyName, policyVersion, policyContentHash));
    }

    @Override
    public void initializeLedger(final String userId, final String documentId, final String inputDocumentHash, final String filename,
                                 final String policyName, final int policyVersion, final String policyContentHash, final String effectiveHash) throws Exception {
        final LedgerEntity entry = new LedgerEntity(userId, documentId, EMPTY_ENTRY, EMPTY_ENTRY, 0, inputDocumentHash, GENESIS, filename, "",
                policyName, policyVersion, policyContentHash);
        entry.setEffectiveHash(effectiveHash);
        entry.setHash(entry.calculateHash());
        addTransaction(entry);
    }

    @Override
    public void addTransaction(final LedgerEntity ledgerEntity) {
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
        requireChainId(ledgerEntity.getUserId(), ledgerEntity.getDocumentId());
        // Fences sealing and purging against appends across all instances.
        if (!beginWriting(ledgerEntity.getUserId(), ledgerEntity.getDocumentId())) {
            throw new IllegalStateException("Ledger chain is not open for writing.");
        }
        try {
            insertEntry(ledgerEntity);
            endWriting(ledgerEntity.getUserId(), ledgerEntity.getDocumentId(), ledgerEntity.getTimestamp());
        } catch (RuntimeException ex) {
            // An uncertain/failed insert cannot turn into a completed, purgeable chain.
            markWriteFailed(ledgerEntity.getUserId(), ledgerEntity.getDocumentId());
            throw ex;
        }
    }

    private static void requireChainId(final String userId, final String documentId) {
        if (userId == null || documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("A ledger chain requires an owner and document ID.");
        }
    }

    @Override
    public void completeChain(final String userId, final String documentId) {
        requireChainId(userId, documentId);
        if (!complete(userId, documentId, new Date())) {
            throw new IllegalStateException("Ledger chain could not be completed.");
        }
    }

    @Override
    public boolean isChainValid(final String userId, final String documentId) {
        return validateChain(userId, documentId).valid();
    }

    @Override
    public ChainValidation validateChain(final String userId, final String documentId) {
        final List<LedgerEntity> chain;
        try {
            chain = getChain(userId, documentId);
        } catch (final UnreadableLedgerEntryException e) {
            LOGGER.warn("The ledger chain for document {} could not be validated.", documentId, e);
            return ChainValidation.unverifiable();
        }
        // Only the checks themselves are caught. A storage failure while finding a signing key is thrown, since it
        // says nothing about the chain.
        try {
            return checkChain(chain, documentId);
        } catch (final NoSuchAlgorithmException e) {
            LOGGER.warn("The ledger chain for document {} could not be validated.", documentId, e);
            return ChainValidation.unverifiable();
        }
    }

    private ChainValidation checkChain(final List<LedgerEntity> chain, final String documentId)
            throws NoSuchAlgorithmException {

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

    @Override
    public Listings.Page<LedgerEntity> listChains(final String requestId, final String userId, final String q,
                                                  final Listings.Sort sort, final int offset, final int limit,
                                                  final String source) {

        final boolean searching = q != null && !q.isBlank();
        final Listings.Page<LedgerEntity> page = findChainHeads(userId, searching ? q : null, sort, offset, limit);

        for (final LedgerEntity head : page.items()) {
            if (head.isUnreadable()) {
                LOGGER.warn("The head entry {} of the ledger chain for document {} could not be read.",
                        head.getId(), head.getDocumentId(), head.getUnreadableCause());
            }
        }

        // Audited with a hash of the search term, never the term itself, which may be sensitive.
        if (userId != null) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_QUERY, userId, null, source,
                    searching ? "searchTermHash: " + DigestUtils.sha256Hex(q) : null);
        }

        return page;

    }

    /** The references of the holds, for a refusal. */
    private static String references(final List<LegalHoldEntity> holds) {
        return holds.stream().map(LegalHoldEntity::getReference).reduce((a, b) -> a + ", " + b).orElse("unknown");
    }

    @Override
    public ServiceResponse deleteChainsByUserIdAndOlderThan(final String requestId,
                                                             final String userId,
                                                             final int daysToKeep) {

        if (daysToKeep < 0) {
            return new ServiceResponse("daysToKeep must be zero or greater.", false, 400);
        }
        return evidenceGuard.execute(userId, "purge_by_age", () -> {
            if (legalHoldDataService.hasAnyHold(userId)) {
                final String refs = references(legalHoldDataService.findAllHoldsForUser(userId));
                auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                        userId, null, null,
                        "operation: purge_by_age, daysToKeep: " + daysToKeep + ", blocking holds: " + refs);
                return new ServiceResponse(
                        "Purge blocked by legal hold(s): " + refs + ". Release all holds before purging.",
                        false, 423);
            }

            final Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_MONTH, -daysToKeep);

            long deletedCount = 0;
            long deletedChains = 0;
            for (final String documentId : findCompletedBefore(userId, cal.getTime())) {
                // Sealed chains cannot acquire another writer. On failure the chain stays retryable.
                deletedCount += deleteEntries(userId, documentId);
                markPurged(userId, documentId);
                deletedChains++;
            }

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId,
                    null, null, "deletedCount: " + deletedCount + ", deletedChains: " + deletedChains
                            + ", daysToKeep: " + daysToKeep);

            return new ServiceResponse("Deleted " + deletedCount + " ledger entries in " + deletedChains
                    + " completed chains older than " + daysToKeep + " days.", true, 200);
        });
    }

    @Override
    public ServiceResponse deleteByDocumentId(final String requestId, final String userId,
                                               final String documentId, final String source) {

        return evidenceGuard.execute(userId, "delete_document_chain", () -> {
            if (legalHoldDataService.isProtectedDocument(userId, documentId)) {
                final String refs = references(legalHoldDataService.findBlockingHoldsForDocument(userId, documentId));
                auditEventPublisher.auditEvent(requestId, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION,
                        userId, null, null,
                        "operation: delete_document_chain, documentId: " + documentId
                                + ", blocking holds: " + refs);
                return new ServiceResponse(
                        "Deletion blocked by legal hold(s): " + refs
                                + ". Release the hold(s) before deleting this chain.",
                        false, 423);
            }

            requireChainId(userId, documentId);
            final ChainState state = chainState(userId, documentId);
            if (state == null) return new ServiceResponse("Ledger chain not found.", false, 404);
            if (state != ChainState.COMPLETE && state != ChainState.PURGED) {
                return new ServiceResponse("Ledger publication is active or requires recovery. Only completed chains can be deleted.",
                        false, 409);
            }
            // The tombstone is kept even when deleting entries fails. Neither a delayed writer nor a new
            // genesis can reopen this document ID; retrying deletion remains safe.
            if (!markPurged(userId, documentId)) {
                throw new IllegalStateException("Ledger chain deletion state could not be established.");
            }
            deleteEntries(userId, documentId);
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEDGER_DELETED, userId,
                    null, source, "documentId: " + documentId);

            return ServiceResponse.success();
        });
    }

}
