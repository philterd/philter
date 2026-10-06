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

import ai.philterd.philter.data.entities.UserEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The calling key's user, with the deployment's MFA settings, which a user who is not an administrator
 * cannot read from {@code GET /api/settings} but needs to decide whether to offer enrollment.
 */
public class CurrentUserResponse extends UserResponse {

    private final boolean mfaAvailable;
    private final boolean mfaRequired;

    public CurrentUserResponse(final UserEntity user, final boolean mfaAvailable, final boolean mfaRequired) {
        super(user);
        this.mfaAvailable = mfaAvailable;
        // Required has no effect unless MFA is available, as at sign-in.
        this.mfaRequired = mfaAvailable && mfaRequired;
    }

    @Schema(description = "Whether this deployment lets users enroll in MFA.")
    public boolean isMfaAvailable() { return mfaAvailable; }

    @Schema(description = "Whether this deployment makes every user who signs in enroll in MFA. Never true while mfaAvailable is false.")
    public boolean isMfaRequired() { return mfaRequired; }

}
