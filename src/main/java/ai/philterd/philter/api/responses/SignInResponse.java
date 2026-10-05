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

import ai.philterd.philter.data.entities.ApiKeyEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Response to a successful {@code POST /api/sign-in}: the session key, returned once. */
public class SignInResponse {

    private final String apiKey;
    private final String username;
    private final List<String> scopes;
    private final Date expiresAt;
    private final Date idleExpiresAt;
    private final boolean passwordChangeRequired;
    private final boolean mfaEnrollmentRequired;

    public SignInResponse(final String username, final ApiKeyEntity sessionKey) {
        this.apiKey = sessionKey.getApiKey();
        this.username = username;
        this.scopes = new ArrayList<>(sessionKey.getScopes());
        this.expiresAt = sessionKey.getExpiresAt();
        this.idleExpiresAt = sessionKey.getIdleExpiresAt();
        this.passwordChangeRequired = sessionKey.isPasswordChangeOnly();
        this.mfaEnrollmentRequired = sessionKey.isMfaEnrollmentOnly();
    }

    @Schema(description = "The session key. Send it as a bearer token. It is returned here and nowhere else.")
    public String getApiKey() { return apiKey; }

    public String getUsername() { return username; }

    public List<String> getScopes() { return scopes; }

    @Schema(description = "When the key's maximum lifetime ends.")
    public Date getExpiresAt() { return expiresAt; }

    @Schema(description = "When the key expires unless it is used first. Each request moves it forward.")
    public Date getIdleExpiresAt() { return idleExpiresAt; }

    @Schema(description = "Whether the password must be changed first. If so, the key can only change the "
            + "password with PUT /api/users/me/password and sign out.")
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }

    @Schema(description = "Whether the user must enroll in MFA first. If so, the key can only enroll with "
            + "POST /api/users/me/mfa and POST /api/users/me/mfa/confirm, and sign out.")
    public boolean isMfaEnrollmentRequired() { return mfaEnrollmentRequired; }

}
