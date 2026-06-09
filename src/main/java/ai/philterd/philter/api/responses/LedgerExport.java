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
 * The portable representation of a redaction-ledger chain, returned by the ledger export endpoint.
 * It contains the full ordered entries for one document so the chain can be archived externally and
 * later re-verified (each entry carries its {@code hash} and {@code previousHash}).
 *
 * <p>Unlike the context export — which exposes only token hashes — a ledger export contains the
 * decrypted token and replacement values, because the ledger's purpose is to record exactly what was
 * redacted to what. Treat an export as sensitive and store/transmit it securely.
 */
public class LedgerExport {

    /**
     * The current export schema version. Bumped only on a breaking format change. Version 2 added the
     * governing policy stamp ({@code policyName}, {@code policyVersion}, {@code policyContentHash}) to
     * each entry.
     */
    public static final int CURRENT_VERSION = 2;

    private final int version;
    private final String documentId;
    private final int count;
    private final List<LedgerEntryView> entries;

    public LedgerExport(final String documentId, final List<LedgerEntryView> entries) {
        this.version = CURRENT_VERSION;
        this.documentId = documentId;
        this.entries = entries;
        this.count = entries != null ? entries.size() : 0;
    }

    public int getVersion() {
        return version;
    }

    public String getDocumentId() {
        return documentId;
    }

    public int getCount() {
        return count;
    }

    public List<LedgerEntryView> getEntries() {
        return entries;
    }

}
