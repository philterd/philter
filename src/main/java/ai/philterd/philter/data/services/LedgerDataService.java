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

import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.model.ServiceResponse;

import java.util.List;

/**
 * The redaction ledger: a hash-chained, signed record of each redaction, kept as evidence.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>Ledger entries never expire on their own. They are removed only by an explicit, audited deletion or
 *   purge, and never while a legal hold protects them (see {@link LegalHoldDataService}).</li>
 *   <li>Appends to a chain keep its hash chain intact, and a sealed chain accepts no further entries.</li>
 *   <li>Deletions and hold changes for one owner are serialized across every instance, so a hold set
 *   concurrently with a deletion either blocks it or is set after it.</li>
 *   <li>Token and replacement values are stored encrypted, and what is written is read back unchanged; an
 *   entry that can no longer be decrypted is still listed, marked unreadable.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping chains, sort by the fields
 *   the API offers, and search as case-insensitive plain text (see {@link Listings}).</li>
 * </ul>
 */
public interface LedgerDataService {

    String GENESIS = "[genesis]";

    void initializeLedger(final String userId, final String documentId, final String inputDocumentHash, final String filename,
            final String policyName, final int policyVersion, final String policyContentHash) throws Exception;

    void initializeLedger(final String userId, final String documentId, final String inputDocumentHash, final String filename,
            final String policyName, final int policyVersion, final String policyContentHash, final String effectiveHash) throws Exception;

    void addTransaction(final LedgerEntity ledgerEntity);

    /** Seals successfully recorded evidence. Further appends to this document ID are rejected. */
    void completeChain(final String userId, final String documentId);

    /** Evidence is append-only; an inherited update must not bypass signed insertion. */
    /**
     * The result of checking a chain. {@code error} is set, and the other fields are meaningless, when the
     * check could not be completed, such as an entry that can no longer be read.
     *
     * <p>Hash-chain consistency and signature authenticity are separate checks. An unsigned entry is
     * unauthenticated and makes the chain invalid, even when every hash and link is consistent.
     */
    record ChainValidation(boolean hashChainValid, boolean signaturesValid,
            int signedEntries, int unsignedEntries, String error) {

        public ChainValidation(final boolean hashChainValid, final boolean signaturesValid,
            final int signedEntries, final int unsignedEntries) {
            this(hashChainValid, signaturesValid, signedEntries, unsignedEntries, null);
        }

        public static ChainValidation unverifiable() {
            return new ChainValidation(false, false, 0, 0, CHAIN_UNVERIFIABLE);
        }

        public boolean valid() {
            return error == null && hashChainValid && signaturesValid && unsignedEntries == 0;
        }
    }

    /** Reported when a chain cannot be checked. Says nothing about the cause, which is in the log. */
    String CHAIN_UNVERIFIABLE =
            "The chain could not be validated, so it is not reported as valid. An entry could not be read or checked.";

    boolean isChainValid(final String userId, final String documentId);

    /**
     * Checks a chain's hashes, links, and signatures. A chain that cannot be checked, such as one with an
     * entry that no longer decrypts or a field of the wrong type, is reported as unverifiable rather than
     * thrown: a chain that cannot be validated is not a valid chain. A database failure still throws, since
     * it says nothing about the chain.
     */
    ChainValidation validateChain(final String userId, final String documentId);

    /** Whether the owner has any entry for the document, without reading one. */
    boolean chainExists(final String userId, final String documentId);

    /**
     * A page of chain heads, the user's or every user's when {@code userId} is null, whose document id or
     * file name contains {@code q} when one is given, and their total. A user's listing is audited as a
     * ledger query, with a hash of the search term rather than the term.
     */
    Listings.Page<LedgerEntity> listChains(final String requestId, final String userId, final String q,
            final Listings.Sort sort, final int offset, final int limit,
            final String source);

    /** Counts the chain heads a search matches. Does not audit; the search it accompanies does. */
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
    ServiceResponse deleteChainsByUserIdAndOlderThan(final String requestId,
            final String userId,
            final int daysToKeep);

    /**
     * The owner's entries for the document in chain order: by {@link LedgerEntity#getChainPosition() position},
     * the genesis entry first. The order never depends on ids or timestamps, which entries written close together
     * can share or can have out of order.
     */
    List<LedgerEntity> getChain(final String userId, final String documentId);

    /** The owner's last entry for the document, by position, or null when there is none. */
    LedgerEntity getLatestTransaction(final String userId, final String documentId);

    /**
     * Deletes all ledger entries for the given document.
     *
     * <p><strong>Hold enforcement:</strong> returns 423 if any {@code document_chain} hold covers
     * this document ID, or if any {@code user}-scoped hold exists for the owner. The blocked
     * attempt is audited as {@code legal_hold_blocked_deletion}.
     */
    ServiceResponse deleteByDocumentId(final String requestId, final String userId,
            final String documentId, final String source);

    /**
     * Deletes all ledger entries for the given user. Intended for administrative bulk removal.
     *
     * <p><strong>Hold enforcement:</strong> returns 423 if any active hold exists for this user.
     * The blocked attempt is audited as {@code legal_hold_blocked_deletion}.
     */
}
