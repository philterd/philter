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

import ai.philterd.philter.data.services.CustomListDataService;
import io.swagger.v3.oas.annotations.media.Schema;

/** A custom list create refused with {@code 409 Conflict}, in the same shape as the other conflicts. */
public class CustomListConflictResponse {

    /** The owner already has a list with this name. */
    public static final String REASON_LIST_EXISTS = CustomListDataService.REASON_LIST_EXISTS;

    private final String message;
    private final String reason;

    public CustomListConflictResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the list was not created: list_exists (the owner already has a list with this name).",
            allowableValues = {REASON_LIST_EXISTS})
    public String getReason() { return reason; }

}
