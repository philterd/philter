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

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/** A policy as {@code GET /api/policies} lists it, without its content. */
public class PolicySummary {

    private final String name;
    private final String description;
    private final int revision;
    private final Date createdAt;
    private final Date updatedAt;
    private final String owner;

    public PolicySummary(final String name, final String description, final int revision, final Date createdAt,
                         final Date updatedAt, final String owner) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.revision = revision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.owner = owner;
    }

    @Schema(description = "The policy's name. A managed policy's begins with managed_.")
    public String getName() { return name; }

    @Schema(description = "The policy's description, or an empty string if it has none.")
    public String getDescription() { return description; }

    @Schema(description = "The policy's current revision.")
    public int getRevision() { return revision; }

    @Schema(description = "When the policy was created.")
    public Date getCreatedAt() { return createdAt; }

    @Schema(description = "When the policy was last changed.")
    public Date getUpdatedAt() { return updatedAt; }

    @Schema(description = "The username of the user the policy belongs to. Present only in an all_users listing.")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getOwner() { return owner; }

}
