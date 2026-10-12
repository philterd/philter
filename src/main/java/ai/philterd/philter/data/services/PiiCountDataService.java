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

import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

/**
 * Reads the PII count aggregates that {@link ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher}
 * records for Philter Diffuse: per context and day, how many redactions found each PII type. Only counts, never
 * any PII.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>Listings return their total and page by offset without repeating or skipping aggregates, ordered by day,
 *   most recent first, then by context.</li>
 *   <li>Each aggregate's counts are the counts recorded for its context and day, read back unchanged.</li>
 * </ul>
 */
public interface PiiCountDataService {

    /**
     * The counts for one context and day.
     *
     * @param context        The context the redactions ran in.
     * @param day            The UTC day the redactions ran on.
     * @param counts         For each PII type, how many redactions found at least one of it.
     * @param totalDocuments How many redactions found any PII.
     * @param updatedAt      When a redaction was last counted.
     */
    record Aggregate(String context, LocalDate day, Map<String, Long> counts, long totalDocuments, Date updatedAt) {
    }

    /**
     * A page of the aggregates for the UTC days from {@code from} through {@code to}, both inclusive, and their
     * total. A null {@code from}, {@code to}, or {@code context} does not narrow the listing.
     */
    Listings.Page<Aggregate> list(final LocalDate from, final LocalDate to, final String context, final int offset,
            final int limit);

}
