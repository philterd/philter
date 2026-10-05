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

/**
 * A sign-in refused with {@code 429 Too Many Requests}. Both refusals share the status and the
 * {@code Retry-After} header, so {@code reason} is what lets a client tell them apart without reading the
 * message, which is written for people and may change.
 */
public class SignInThrottledResponse {

    /** The username is locked after repeated failed sign-ins. */
    public static final String REASON_LOCKED = "locked";

    /** The client address is over the sign-in rate limit. */
    public static final String REASON_RATE_LIMITED = "rate_limited";

    private final String message;
    private final String reason;

    public SignInThrottledResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the sign-in was refused: locked (the username is locked after repeated failures) "
            + "or rate_limited (the client address is over the rate limit).",
            allowableValues = {REASON_LOCKED, REASON_RATE_LIMITED})
    public String getReason() { return reason; }

}
