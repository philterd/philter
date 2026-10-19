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

import java.util.Date;
import java.util.Map;

/** The PII counts for one user's context and day, as returned by the PII counts API. Only counts, never any PII. */
public class PiiCountView {

    private final String id;
    private final String owner;
    private final String context;
    private final String day;
    private final Map<String, Long> counts;
    private final long totalDocuments;
    private final Date updatedAt;

    public PiiCountView(final String id, final String owner, final String context, final String day,
                        final Map<String, Long> counts, final long totalDocuments, final Date updatedAt) {
        this.id = id;
        this.owner = owner;
        this.context = context;
        this.day = day;
        this.counts = counts;
        this.totalDocuments = totalDocuments;
        this.updatedAt = updatedAt;
    }

    @Schema(description = "Identifies this user's context and day, the same on every read, so a client paging through "
            + "the counts can tell buckets apart without their names.")
    public String getId() {
        return id;
    }

    @Schema(description = "The username of the user whose redactions these are. Given for the caller's own counts, and "
            + "for other users' only when ADMIN_CROSS_USER_ACCESS_ENABLED is true; otherwise absent.")
    public String getOwner() {
        return owner;
    }

    @Schema(description = "The context the redactions ran in. Given for the caller's own counts, and for other users' "
            + "only when ADMIN_CROSS_USER_ACCESS_ENABLED is true; otherwise absent.")
    public String getContext() {
        return context;
    }

    @Schema(description = "The UTC day the redactions ran on, as YYYY-MM-DD.", example = "2026-10-01")
    public String getDay() {
        return day;
    }

    @Schema(description = "For each PII type, how many redactions that day found at least one of it.",
            example = "{\"ssn\": 1240, \"email-address\": 8900}")
    public Map<String, Long> getCounts() {
        return counts;
    }

    @Schema(description = "How many redactions that day found any PII.")
    public long getTotalDocuments() {
        return totalDocuments;
    }

    @Schema(description = "When a redaction was last counted.")
    public Date getUpdatedAt() {
        return updatedAt;
    }

}
