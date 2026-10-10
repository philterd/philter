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

/** A page of a policy's retained versions and how many there are. */
public class GetPolicyVersionsResponse {

    private final List<PolicyVersionSummary> versions;
    private final long total;

    public GetPolicyVersionsResponse(final List<PolicyVersionSummary> versions, final long total) {
        this.versions = versions;
        this.total = total;
    }

    @Schema(description = "The versions on this page.")
    public List<PolicyVersionSummary> getVersions() { return versions; }

    @Schema(description = "How many versions the policy has retained.")
    public long getTotal() { return total; }

}
