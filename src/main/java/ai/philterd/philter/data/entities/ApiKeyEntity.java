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
package ai.philterd.philter.data.entities;

import org.bson.Document;
import ai.philterd.philter.model.ApiKeyScope;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ApiKeyEntity extends AbstractEntity {

    private ObjectId id;
    private String apiKeyHash;
    private String apiKeyPrefix;
    // API keys are soft-deleted: a deleted key is revoked (it can never authenticate again) but the
    // row is retained, marked deleted with the time of deletion, so audit entries that reference the
    // key id still resolve to it. Unlike a deactivated user, a deleted key cannot be reactivated.
    private boolean deleted;
    private Date deletedAt;
    private Date timestamp;
    private transient String apiKey;
    private ObjectId userId;
    // True for a key seeded at startup from PHILTER_BOOTSTRAP_API_KEY, so the UI can flag that the
    // bootstrap key is still in use and nudge the admin to replace it with one of their own.
    private boolean bootstrap;

    /** The scopes this key carries. Empty means the key can call nothing. */
    private Set<String> scopes = new LinkedHashSet<>();

    public static ApiKeyEntity fromDocument(final Document document) {
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setId(document.getObjectId("_id"));
        apiKeyEntity.setUserId(document.getObjectId("user_id"));

        // Read hash and prefix from document
        apiKeyEntity.setApiKeyHash(document.getString("api_key_hash"));
        apiKeyEntity.setApiKeyPrefix(document.getString("api_key_prefix"));

        apiKeyEntity.setDeleted(document.getBoolean("deleted", false));
        apiKeyEntity.setDeletedAt(document.getDate("deleted_at"));
        apiKeyEntity.setTimestamp(document.getDate("timestamp"));
        apiKeyEntity.setBootstrap(document.getBoolean("bootstrap", false));

        // A key with no scopes recorded can call nothing: scopes are always written at
        // creation, so their absence is a malformed key rather than a legacy one.
        final List<String> scopes = document.getList("scopes", String.class);
        apiKeyEntity.setScopes(scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes));

        return apiKeyEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("api_key_hash", apiKeyHash);
        document.put("api_key_prefix", apiKeyPrefix);
        document.put("deleted", deleted);
        document.put("deleted_at", deletedAt);
        document.put("timestamp", timestamp);
        document.put("bootstrap", bootstrap);
        document.put("scopes", new ArrayList<>(scopes));
        return document;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeletedAt(final Date deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Date getDeletedAt() {
        return deletedAt;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(final String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(final String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public boolean isBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(final boolean bootstrap) {
        this.bootstrap = bootstrap;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public void setScopes(final Set<String> scopes) {
        this.scopes = scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes);
    }

    /** Whether this key carries the given scope. */
    public boolean hasScope(final ApiKeyScope scope) {
        // Null-safe on both sides: a key deserialized from the cache with no scopes field must deny,
        // not throw. An exception here would surface as a 500 rather than a clean refusal.
        return scope != null && scopes != null && scopes.contains(scope.getScope());
    }

}
