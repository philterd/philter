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

/** A deleted policy whose version history is kept, as GET /api/policies?deleted=true lists it. */
public class DeletedPolicySummary {

    private final String name;
    private final int latestRevision;
    private final Date deletedAt;
    private final String deletedBy;

    public DeletedPolicySummary(final String name, final int latestRevision, final Date deletedAt, final String deletedBy) {
        this.name = name;
        this.latestRevision = latestRevision;
        this.deletedAt = deletedAt;
        this.deletedBy = deletedBy;
    }

    @Schema(description = "The policy's name. Read its history with GET /api/policies/{policyName}/versions.")
    public String getName() { return name; }

    @Schema(description = "The most recent retained revision.")
    public int getLatestRevision() { return latestRevision; }

    @Schema(description = "When the policy was deleted, or null for a policy deleted before Philter recorded deletions.")
    public Date getDeletedAt() { return deletedAt; }

    @Schema(description = "The username of the user who deleted it, or null when that is not known.")
    public String getDeletedBy() { return deletedBy; }

}
