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
 * A page of redaction-ledger chain heads (one genesis entry per redacted document) together with the
 * total number of chains for the user, so a client can page through them.
 */
public class GetLedgerResponse {

    private final List<LedgerEntryView> chains;
    private final int total;

    public GetLedgerResponse(final List<LedgerEntryView> chains, final int total) {
        this.chains = chains;
        this.total = total;
    }

    public List<LedgerEntryView> getChains() {
        return chains;
    }

    public int getTotal() {
        return total;
    }

}
