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

import java.util.List;

/** A custom list as {@code GET /api/lists/{name}} returns it: its items and description. */
public class CustomListResponse {

    private final List<String> items;
    private final String description;

    public CustomListResponse(List<String> items, String description) {
        this.items = items;
        this.description = description == null ? "" : description;
    }

    @Schema(description = "The list's items.")
    public List<String> getItems() {
        return items;
    }

    @Schema(description = "The list's description, or an empty string if it has none.")
    public String getDescription() {
        return description;
    }

}
