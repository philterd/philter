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

/** Response body for a successful {@code POST /api/users}. */
public class CreatedUserResponse {

    private final String username;
    private final String role;

    public CreatedUserResponse(final String username, final String role) {
        this.username = username;
        this.role = role;
    }

    public String getUsername() { return username; }

    public String getRole() { return role; }

}
