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

import ai.philterd.philter.data.services.RedactListsDataService;
import io.swagger.v3.oas.annotations.media.Schema;

/** A redact list write refused with {@code 409 Conflict}, in the same shape as the other conflicts. */
public class RedactListConflictResponse {

    /** The list changed since the revision the client gave in If-Match. */
    public static final String REASON_REDACT_LIST_CHANGED = RedactListsDataService.REASON_REDACT_LIST_CHANGED;

    private final String message;
    private final String reason;

    public RedactListConflictResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the list was not written: redact_list_changed (the list is no longer at the revision "
            + "given in If-Match).", allowableValues = {REASON_REDACT_LIST_CHANGED})
    public String getReason() { return reason; }

}
