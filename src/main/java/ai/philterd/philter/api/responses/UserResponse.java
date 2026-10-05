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

import java.util.Date;

/** A user as the API returns it. Says whether a password is set, never the password or its hash. */
public class UserResponse {

    private final String username;
    private final String email;
    private final String role;
    private final boolean active;
    private final Date created;
    private final Date deactivatedAt;
    private final boolean passwordSet;
    private final boolean passwordChangeRequired;

    public UserResponse(final UserEntity user) {
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.role = user.getRole();
        this.active = !user.isDeactivated();
        // The ObjectId carries the creation time; users have no separate created field.
        this.created = user.getId() == null ? null : user.getId().getDate();
        this.deactivatedAt = user.getDeactivatedAt();
        this.passwordSet = user.getPassword() != null;
        this.passwordChangeRequired = user.isPasswordChangeRequired();
    }

    public String getUsername() { return username; }

    public String getEmail() { return email; }

    public String getRole() { return role; }

    public boolean isActive() { return active; }

    public Date getCreated() { return created; }

    public Date getDeactivatedAt() { return deactivatedAt; }

    @Schema(description = "Whether the user has a password. A user without one can only use API keys.")
    public boolean isPasswordSet() { return passwordSet; }

    @Schema(description = "Whether the user must change their password at next sign-in, because an administrator set it.")
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }

}
