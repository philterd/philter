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

import java.util.Map;

/**
 * A context's settings, its size, and its entries counted by filter type. The filter-type counts and
 * {@code untyped} sum to {@code size}.
 */
public class GetContextResponse {

    private final long size;
    private final Map<String, Long> filterTypes;
    private final long untyped;
    private final boolean entityTypeDisambiguation;
    private final boolean ledger;

    public GetContextResponse(final long size, final Map<String, Long> filterTypes, final long untyped,
                              final boolean entityTypeDisambiguation, final boolean ledger) {
        this.size = size;
        this.filterTypes = filterTypes;
        this.untyped = untyped;
        this.entityTypeDisambiguation = entityTypeDisambiguation;
        this.ledger = ledger;
    }

    /** Every entry in the context. */
    public long getSize() {
        return size;
    }

    /** Entries by filter type, sorted by filter type. */
    public Map<String, Long> getFilterTypes() {
        return filterTypes;
    }

    /** Entries stored without a filter type, which only an import can create. */
    public long getUntyped() {
        return untyped;
    }

    /** Whether entity-type span disambiguation applies to redactions in this context. */
    public boolean isEntityTypeDisambiguation() {
        return entityTypeDisambiguation;
    }

    /** Whether redactions in this context are recorded in the ledger. */
    public boolean isLedger() {
        return ledger;
    }

}
