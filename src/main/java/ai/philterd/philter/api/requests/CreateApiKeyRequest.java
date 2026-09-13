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

import java.util.List;

/**
 * Request body for {@code POST /api/users/{username}/api-keys}.
 *
 * <p>The scopes are required rather than defaulted. A key minted without the caller saying what it
 * is for would carry whatever the default happened to be, which for a credential is the wrong way
 * round.
 */
public class CreateApiKeyRequest {

    private List<String> scopes;

    public List<String> getScopes() { return scopes; }
    public void setScopes(final List<String> scopes) { this.scopes = scopes; }

}
