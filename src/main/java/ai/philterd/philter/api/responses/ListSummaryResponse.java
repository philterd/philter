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

/** A custom list as {@code GET /api/lists} lists it: its name, description, and size, without its items. */
public class ListSummaryResponse {

    private final String name;
    private final String description;
    private final int size;
    private final String owner;

    public ListSummaryResponse(final String name, final String description, final int size, final String owner) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.size = size;
        this.owner = owner;
    }

    public String getName() { return name; }

    @Schema(description = "The list's description, or an empty string if it has none.")
    public String getDescription() { return description; }

    @Schema(description = "How many items the list holds.")
    public int getSize() { return size; }

    @Schema(description = "The username of the user the list belongs to. Present only in an all_users listing.")
    public String getOwner() { return owner; }

}
