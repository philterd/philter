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

import java.util.Date;

/**
 * A single redaction-ledger entry as returned by the ledger API. A document's chain is an ordered
 * sequence of these, starting with a genesis entry (empty token/replacement) followed by one entry
 * per redaction. {@code hash} and {@code previousHash} link the chain so it can be verified.
 */
public class LedgerEntryView {

    private String documentId;
    private String filename;
    private String type;
    private String token;
    private String replacement;
    private long startPosition;
    private String documentHash;
    private String previousHash;
    private String hash;
    private Date timestamp;

    public LedgerEntryView() {
    }

    public LedgerEntryView(final String documentId, final String filename, final String type, final String token,
                           final String replacement, final long startPosition, final String documentHash,
                           final String previousHash, final String hash, final Date timestamp) {
        this.documentId = documentId;
        this.filename = filename;
        this.type = type;
        this.token = token;
        this.replacement = replacement;
        this.startPosition = startPosition;
        this.documentHash = documentHash;
        this.previousHash = previousHash;
        this.hash = hash;
        this.timestamp = timestamp;
    }

    public String getDocumentId() {
        return documentId;
    }

    public String getFilename() {
        return filename;
    }

    public String getType() {
        return type;
    }

    public String getToken() {
        return token;
    }

    public String getReplacement() {
        return replacement;
    }

    public long getStartPosition() {
        return startPosition;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public String getHash() {
        return hash;
    }

    public Date getTimestamp() {
        return timestamp;
    }

}
