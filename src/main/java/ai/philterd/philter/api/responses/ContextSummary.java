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

/** A context as {@code GET /api/contexts} lists it, without its entries. */
public class ContextSummary {

    private final String name;
    private final Date createdAt;
    private final String owner;

    public ContextSummary(final String name, final Date createdAt, final String owner) {
        this.name = name;
        this.createdAt = createdAt;
        this.owner = owner;
    }

    public String getName() { return name; }

    @Schema(description = "When the context was created.")
    public Date getCreatedAt() { return createdAt; }

    @Schema(description = "The username of the user the context belongs to. Present only in an all_users listing.")
    public String getOwner() { return owner; }

}
