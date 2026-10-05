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

/** An API key as the API lists it. Carries the prefix only, never the key or its hash. */
public class ApiKeyResponse {

    private final String id;
    private final String prefix;
    private final List<String> scopes;
    private final Date created;
    private final boolean bootstrap;
    private final boolean session;
    private final Date expiresAt;
    private final Date idleExpiresAt;
    private final Date lastUsedAt;

    public ApiKeyResponse(final ApiKeyEntity apiKeyEntity) {
        this.id = apiKeyEntity.getId().toHexString();
        this.prefix = apiKeyEntity.getApiKeyPrefix();
        this.scopes = new ArrayList<>(apiKeyEntity.getScopes());
        this.created = apiKeyEntity.getTimestamp();
        this.bootstrap = apiKeyEntity.isBootstrap();
        this.session = apiKeyEntity.isSession();
        this.expiresAt = apiKeyEntity.getExpiresAt();
        this.idleExpiresAt = apiKeyEntity.getIdleExpiresAt();
        this.lastUsedAt = apiKeyEntity.getLastUsedAt();
    }

    public String getId() { return id; }

    public String getPrefix() { return prefix; }

    public List<String> getScopes() { return scopes; }

    public Date getCreated() { return created; }

    public boolean isBootstrap() { return bootstrap; }

    @Schema(description = "Whether this is a session key, issued when a person signed in, rather than a long-lived key.")
    public boolean isSession() { return session; }

    @Schema(description = "Session keys only: when the key's maximum lifetime ends. Null for a long-lived key.")
    public Date getExpiresAt() { return expiresAt; }

    @Schema(description = "Session keys only: when the key expires unless it is used first. Null for a long-lived key.")
    public Date getIdleExpiresAt() { return idleExpiresAt; }

    @Schema(description = "Session keys only: when the key was last used. Null for a long-lived key.")
    public Date getLastUsedAt() { return lastUsedAt; }

}
