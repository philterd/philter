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

import java.util.Date;

/** Response to {@code POST /api/sign-in} for a user enrolled in MFA: a challenge to complete with a code. */
public class SignInChallengeResponse {

    private final boolean mfaRequired = true;
    private final String challenge;
    private final Date challengeExpiresAt;

    public SignInChallengeResponse(final String challenge, final Date challengeExpiresAt) {
        this.challenge = challenge;
        this.challengeExpiresAt = challengeExpiresAt;
    }

    @Schema(description = "Always true: send the challenge and a code to POST /api/sign-in/mfa.")
    public boolean isMfaRequired() { return mfaRequired; }

    @Schema(description = "Single-use. Any attempt with it, right or wrong, uses it up.")
    public String getChallenge() { return challenge; }

    public Date getChallengeExpiresAt() { return challengeExpiresAt; }

}
