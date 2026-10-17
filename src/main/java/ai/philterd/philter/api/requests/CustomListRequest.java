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
package ai.philterd.philter.api.requests;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Request body for creating or replacing a custom list. */
public class CustomListRequest {

    private List<String> items;
    private String description;

    @Schema(description = "The list's items. Required, with at least one item that is not empty.",
            requiredMode = Schema.RequiredMode.REQUIRED)
    public List<String> getItems() { return items; }
    public void setItems(final List<String> items) { this.items = items; }

    @Schema(description = "The list's description. On create, left out means an empty string. On replace, left out "
            + "keeps the list's description, and an empty string clears it.")
    public String getDescription() { return description; }
    public void setDescription(final String description) { this.description = description; }

}
