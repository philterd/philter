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

import java.util.List;

/**
 * A document's redaction-ledger chain: its document id, whether the hash chain currently verifies,
 * and the ordered entries. The validity endpoint returns this with {@code entries} omitted.
 */
public class LedgerChainResponse {

    private final String documentId;
    private final boolean valid;
    /** Reported apart from {@code valid}: these fail for different reasons and mean different things. */
    private final boolean hashChainValid;
    private final boolean signaturesValid;
    private final int signedEntries;
    private final int unsignedEntries;
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
        this.entries = entries;
    }

    public boolean isHashChainValid() {
        return hashChainValid;
    }

    public boolean isSignaturesValid() {
        return signaturesValid;
    }

    public int getSignedEntries() {
        return signedEntries;
    }

    public int getUnsignedEntries() {
        return unsignedEntries;
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
