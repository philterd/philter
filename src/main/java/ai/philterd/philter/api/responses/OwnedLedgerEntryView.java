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

/** A ledger chain head and the username of the user it belongs to, as listed with {@code all_users}. */
public class OwnedLedgerEntryView extends LedgerEntryView {

    private String owner;

    public OwnedLedgerEntryView() {
    }

    public OwnedLedgerEntryView(final String owner, final String documentId, final String filename, final String type,
                                final String token, final String replacement, final long startPosition,
                                final String documentHash, final String previousHash, final String hash,
                                final Date timestamp, final String policyName, final int policyVersion,
                                final String policyContentHash) {
        super(documentId, filename, type, token, replacement, startPosition, documentHash, previousHash, hash,
                timestamp, policyName, policyVersion, policyContentHash);
        this.owner = owner;
    }

    public String getOwner() { return owner; }

}
