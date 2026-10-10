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

/**
 * The account's always-redact and never-redact lists. Both fields are always present; a list with no
 * saved terms is returned as an empty array rather than null.
 */
public class RedactListsResponse {

    private final List<String> alwaysRedact;
    private final List<String> neverRedact;
    private final long alwaysRedactRevision;
    private final long neverRedactRevision;

    public RedactListsResponse(final List<String> alwaysRedact, final List<String> neverRedact,
                               final long alwaysRedactRevision, final long neverRedactRevision) {
        this.alwaysRedact = alwaysRedact;
        this.neverRedact = neverRedact;
        this.alwaysRedactRevision = alwaysRedactRevision;
        this.neverRedactRevision = neverRedactRevision;
    }

    public List<String> getAlwaysRedact() {
        return alwaysRedact;
    }

    public List<String> getNeverRedact() {
        return neverRedact;
    }

    @Schema(description = "The always-redact list's revision, for If-Match on PUT /api/redact-lists/always.")
    public long getAlwaysRedactRevision() {
        return alwaysRedactRevision;
    }

    @Schema(description = "The never-redact list's revision, for If-Match on PUT /api/redact-lists/never.")
    public long getNeverRedactRevision() {
        return neverRedactRevision;
    }

}
