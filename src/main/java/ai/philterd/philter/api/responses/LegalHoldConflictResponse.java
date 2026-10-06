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

import ai.philterd.philter.data.services.LegalHoldDataService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A legal hold change refused with {@code 409 Conflict}. A duplicate reference and an active evidence
 * operation share the status, so {@code reason} is what lets a client tell them apart.
 */
public class LegalHoldConflictResponse {

    /** The owner already has a hold with this reference. */
    public static final String REASON_HOLD_EXISTS = LegalHoldDataService.REASON_HOLD_EXISTS;

    /** Another evidence or hold operation for the owner is active or requires recovery. */
    public static final String REASON_OPERATION_IN_PROGRESS = LegalHoldDataService.REASON_OPERATION_IN_PROGRESS;

    private final String message;
    private final String reason;

    public LegalHoldConflictResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the hold was not changed: hold_exists (the owner already has a hold with this "
            + "reference) or operation_in_progress (another evidence or hold operation for the owner is active "
            + "or requires recovery).",
            allowableValues = {REASON_HOLD_EXISTS, REASON_OPERATION_IN_PROGRESS})
    public String getReason() { return reason; }

}
