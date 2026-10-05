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

/** Request body for {@code POST /api/sign-in}. */
public class SignInRequest {

    private String username;
    private String password;

    public String getUsername() { return username; }
    public void setUsername(final String username) { this.username = username; }

    @Schema(description = "The user's password. Never logged or audited.")
    public String getPassword() { return password; }
    public void setPassword(final String password) { this.password = password; }

}
