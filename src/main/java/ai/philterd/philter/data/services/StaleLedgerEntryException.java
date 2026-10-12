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

/**
 * A ledger entry was refused because it does not follow its chain's latest entry: its previous hash is not the hash
 * of the entry now last in the chain, or a genesis entry was written to a chain that already has entries. Another
 * writer appended first. Nothing was written and the chain is still open, so the writer can read the chain again
 * and retry.
 */
public class StaleLedgerEntryException extends IllegalStateException {

    public StaleLedgerEntryException(final String documentId, final boolean chainEmpty) {
        super(chainEmpty
                ? "The ledger entry for document " + documentId + " links to an earlier entry, but the chain is empty; "
                        + "it was not saved."
                : "The ledger entry for document " + documentId + " does not follow the chain's latest entry; it was "
                        + "not saved.");
    }

}
