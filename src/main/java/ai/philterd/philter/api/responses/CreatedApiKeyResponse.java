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

import java.util.List;

/**
 * Response body for a successful {@code POST /api/users/{username}/api-keys}. The key value is
 * returned here and nowhere else: only its hash is stored, so this response is the one chance to
 * capture it.
 */
public class CreatedApiKeyResponse {

    private final String username;
    private final String apiKey;
    private final List<String> scopes;

    public CreatedApiKeyResponse(final String username, final String apiKey, final List<String> scopes) {
        this.username = username;
        this.apiKey = apiKey;
        this.scopes = scopes;
    }

    public String getUsername() { return username; }

    public String getApiKey() { return apiKey; }

    public List<String> getScopes() { return scopes; }

}
