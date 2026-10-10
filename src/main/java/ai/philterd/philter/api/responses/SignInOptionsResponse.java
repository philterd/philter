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
 * What a sign-in page needs before anyone has signed in: that password sign-in is enabled, which a
 * successful response itself says, and the rules a new password must meet.
 */
public class SignInOptionsResponse {

    private final LimitsResponse.Password password;

    public SignInOptionsResponse(final LimitsResponse.Password password) {
        this.password = password;
    }

    @Schema(description = "The rules a password must meet, for a page where a person sets one.")
    public LimitsResponse.Password getPassword() { return password; }

}
