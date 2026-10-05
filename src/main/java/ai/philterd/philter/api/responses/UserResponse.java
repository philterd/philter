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

import java.util.Date;

/** A user as the API returns it. Never carries a password, password hash, or MFA secret. */
public class UserResponse {

    private final String username;
    private final String email;
    private final String role;
    private final boolean active;
    private final Date created;
    private final Date deactivatedAt;

    public UserResponse(final UserEntity user) {
        this.username = user.getUsername();
        this.email = user.getEmail();
        this.role = user.getRole();
        this.active = !user.isDeactivated();
        // The ObjectId carries the creation time; users have no separate created field.
        this.created = user.getId() == null ? null : user.getId().getDate();
        this.deactivatedAt = user.getDeactivatedAt();
    }

    public String getUsername() { return username; }

    public String getEmail() { return email; }

    public String getRole() { return role; }

    public boolean isActive() { return active; }

    public Date getCreated() { return created; }

    public Date getDeactivatedAt() { return deactivatedAt; }

}
