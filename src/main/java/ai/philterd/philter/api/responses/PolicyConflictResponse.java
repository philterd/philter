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

import ai.philterd.philter.data.services.PolicyDataService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A policy save or rollback refused with {@code 409 Conflict}. Several causes share the status, so
 * {@code reason} is what lets a client tell them apart without reading the message.
 */
public class PolicyConflictResponse {

    /** The policy is a built-in managed policy, which cannot be changed. */
    public static final String REASON_POLICY_MANAGED = PolicyDataService.REASON_POLICY_MANAGED;

    /** The policy changed after the request read it. */
    public static final String REASON_POLICY_CHANGED = PolicyDataService.REASON_POLICY_CHANGED;

    /** A policy with this name was created after the request checked for one. */
    public static final String REASON_POLICY_EXISTS = PolicyDataService.REASON_POLICY_EXISTS;

    private final String message;
    private final String reason;

    public PolicyConflictResponse(final String message, final String reason) {
        this.message = message;
        this.reason = reason;
    }

    public String getMessage() { return message; }

    @Schema(description = "Why the policy was not changed: policy_managed (it is a built-in managed policy), "
            + "policy_changed (it changed concurrently; reload it and retry), or policy_exists (a policy with "
            + "this name was created concurrently).",
            allowableValues = {REASON_POLICY_MANAGED, REASON_POLICY_CHANGED, REASON_POLICY_EXISTS})
    public String getReason() { return reason; }

}
