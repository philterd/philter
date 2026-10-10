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
 * A page of policies and how many there are. Each item is a policy's name; with all_users, its name and
 * owner; with managed, its name and description; with deleted, a {@link DeletedPolicySummary}.
 */
public class GetPoliciesResponse {

    private final List<?> policies;
    private final long total;

    public GetPoliciesResponse(final List<?> policies, final long total) {
        this.policies = policies;
        this.total = total;
    }

    @Schema(description = "The policies on this page: names, or with all_users, managed, or deleted, objects describing each.")
    public List<?> getPolicies() { return policies; }

    @Schema(description = "How many policies the listing has across every page.")
    public long getTotal() { return total; }

}
