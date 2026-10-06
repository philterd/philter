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

/** A built-in managed policy as {@code GET /api/policies?managed=true} lists it: its name and description. */
public class ManagedPolicySummary {

    private final String name;
    private final String description;

    public ManagedPolicySummary(final String name, final String description) {
        this.name = name;
        this.description = description == null ? "" : description;
    }

    @Schema(description = "The managed policy's name, beginning with managed_.")
    public String getName() { return name; }

    @Schema(description = "What the managed policy redacts, or an empty string if it has no description.")
    public String getDescription() { return description; }

}
