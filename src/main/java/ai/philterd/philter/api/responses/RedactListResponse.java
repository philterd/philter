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

/** One of the account's redact lists, and the revision it is at. */
public class RedactListResponse {

    private final List<String> terms;
    private final long revision;

    public RedactListResponse(final List<String> terms, final long revision) {
        this.terms = terms;
        this.revision = revision;
    }

    @Schema(description = "The list's terms. A list with no saved terms is an empty array.")
    public List<String> getTerms() {
        return terms;
    }

    @Schema(description = "The list's revision, which every write of the list increments. Send it back in If-Match "
            + "when replacing the list so the write is refused if someone changed the list since. A list never "
            + "written is at 0. The ETag header carries the same value.")
    public long getRevision() {
        return revision;
    }

}
