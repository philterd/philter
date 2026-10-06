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

import ai.philterd.philter.model.ApiKeyScope;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

/** Every API key scope, in the order Philter declares them, for a client choosing scopes for a key. */
public class ApiKeyScopesResponse {

    private final List<Scope> scopes = new ArrayList<>();

    public ApiKeyScopesResponse() {
        for (final ApiKeyScope scope : ApiKeyScope.values()) {
            scopes.add(new Scope(scope.getScope(), scope.getDescription()));
        }
    }

    public List<Scope> getScopes() { return scopes; }

    /** One scope: the name a key carries, and what it allows. */
    @Schema(name = "ApiKeyScopeDescription")
    public static class Scope {

        private final String name;
        private final String description;

        public Scope(final String name, final String description) {
            this.name = name;
            this.description = description;
        }

        @Schema(description = "The scope as an API key carries it, for example policies:read.")
        public String getName() { return name; }

        @Schema(description = "What the scope allows, including any requirement for an administrator.")
        public String getDescription() { return description; }

    }

}
