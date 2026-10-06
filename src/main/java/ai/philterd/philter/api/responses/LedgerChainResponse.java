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
package ai.philterd.philter.api.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * A document's redaction-ledger chain: its document id, whether the hash chain currently verifies,
 * and the ordered entries. The validity endpoint returns this with {@code entries} omitted. A chain
 * that could not be checked has {@code valid} false and a {@code validationError}, and leaves out the
 * results of the checks, which did not complete.
 */
public class LedgerChainResponse {

    private final String documentId;
    private final boolean valid;
    /** Reported apart from {@code valid}: these fail for different reasons and mean different things. */
    private final Boolean hashChainValid;
    private final Boolean signaturesValid;
    private final Integer signedEntries;
    private final Integer unsignedEntries;
    private final String validationError;
    private final List<LedgerEntryView> entries;

    public LedgerChainResponse(final String documentId, final boolean valid, final List<LedgerEntryView> entries) {
        this(documentId, valid, valid, valid, 0, 0, entries);
    }

    public LedgerChainResponse(final String documentId, final boolean valid, final boolean hashChainValid,
                               final boolean signaturesValid, final int signedEntries,
                               final int unsignedEntries, final List<LedgerEntryView> entries) {
        this.documentId = documentId;
        this.valid = valid;
        this.hashChainValid = hashChainValid;
        this.signaturesValid = signaturesValid;
        this.signedEntries = signedEntries;
        this.unsignedEntries = unsignedEntries;
        this.validationError = null;
        this.entries = entries;
    }

    private LedgerChainResponse(final String documentId, final String validationError) {
        this.documentId = documentId;
        this.valid = false;
        this.hashChainValid = null;
        this.signaturesValid = null;
        this.signedEntries = null;
        this.unsignedEntries = null;
        this.validationError = validationError;
        this.entries = null;
    }

    /** A chain whose validation could not be completed. Gson leaves out the null fields. */
    public static LedgerChainResponse unverifiable(final String documentId, final String validationError) {
        return new LedgerChainResponse(documentId, validationError);
    }

    public Boolean getHashChainValid() {
        return hashChainValid;
    }

    public Boolean getSignaturesValid() {
        return signaturesValid;
    }

    public Integer getSignedEntries() {
        return signedEntries;
    }

    public Integer getUnsignedEntries() {
        return unsignedEntries;
    }

    @Schema(description = "Why the chain could not be validated. Present only then, with valid false and the "
            + "hash, signature, and entry-count fields left out.")
    public String getValidationError() {
        return validationError;
    }

    public String getDocumentId() {
        return documentId;
    }

    public boolean isValid() {
        return valid;
    }

    public List<LedgerEntryView> getEntries() {
        return entries;
    }

}
