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

/** A ledger request refused because of the stored chain, with a reason a client can act on. */
public class LedgerRefusalResponse {

    /** An entry in the chain could not be read, so the request cannot be completed. */
    public static final String REASON_ENTRY_UNREADABLE = "entry_unreadable";

    private final String message;
    private final String reason;

    public LedgerRefusalResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the request was refused: entry_unreadable (an entry in the chain could not be read).",
            allowableValues = {REASON_ENTRY_UNREADABLE})
    public String getReason() { return reason; }

}
