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
package ai.philterd.philter.api.requests;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The complete contents of one redact list. */
public class RedactListRequest {

    private List<String> terms;

    @Schema(description = "The list's complete contents. An empty array or an omitted field clears the list.")
    public List<String> getTerms() {
        return terms;
    }

    public void setTerms(final List<String> terms) {
        this.terms = terms;
    }

}
