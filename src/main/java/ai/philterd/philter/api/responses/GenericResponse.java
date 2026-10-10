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

/**
 * A message, and for a refusal, a machine-readable reason. Every error Philter returns under /api has
 * this shape. {@code reason} and {@code field} are left out when they do not apply.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenericResponse {

    private final String message;
    private final String reason;
    private final String field;

    public GenericResponse(final String message) {
        this(message, null, null);
    }

    public GenericResponse(final String message, final String reason) {
        this(message, reason, null);
    }

    public GenericResponse(final String message, final String reason, final String field) {
        this.message = message;
        this.reason = reason;
        this.field = field;
    }

    @Schema(description = "What happened, written for a person to read. It may change; use reason in code.")
    public String getMessage() {
        return message;
    }

    @Schema(description = "For an error, why the request was refused, as a stable code a client can act on. The codes "
            + "are listed in the API documentation under Errors.")
    public String getReason() {
        return reason;
    }

    @Schema(description = "For a request refused as invalid, the parameter or body field that was invalid, when one is.")
    public String getField() {
        return field;
    }

}
