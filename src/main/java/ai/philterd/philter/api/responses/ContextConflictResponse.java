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

import ai.philterd.philter.data.services.ContextDataService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A context create refused with {@code 409 Conflict}. A duplicate name and the per-user context limit
 * share the status, so {@code reason} is what lets a client tell them apart without reading the message,
 * which is written for people and may change.
 */
public class ContextConflictResponse {

    /** The caller already has a context with this name. */
    public static final String REASON_CONTEXT_EXISTS = ContextDataService.REASON_CONTEXT_EXISTS;

    /** The caller already has the most contexts a user may have. */
    public static final String REASON_CONTEXT_LIMIT_REACHED = ContextDataService.REASON_CONTEXT_LIMIT_REACHED;

    private final String message;
    private final String reason;

    public ContextConflictResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the context was not created: context_exists (the caller already has a context with "
            + "this name) or context_limit_reached (the caller already has the most contexts a user may have).",
            allowableValues = {REASON_CONTEXT_EXISTS, REASON_CONTEXT_LIMIT_REACHED})
    public String getReason() { return reason; }

}
