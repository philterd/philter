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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.entities.ApiKeyEntity;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Maps {@link ApiKeyEntity} to and from the documents MongoDB stores. */
public final class ApiKeyDocuments {

    private ApiKeyDocuments() {
    }

    public static ApiKeyEntity fromDocument(final Document document) {
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        apiKeyEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));

        // Read hash and prefix from document
        apiKeyEntity.setApiKeyHash(document.getString("api_key_hash"));
        apiKeyEntity.setApiKeyPrefix(document.getString("api_key_prefix"));

        apiKeyEntity.setDeleted(document.getBoolean("deleted", false));
        apiKeyEntity.setDeletedAt(document.getDate("deleted_at"));
        apiKeyEntity.setTimestamp(document.getDate("timestamp"));
        apiKeyEntity.setBootstrap(document.getBoolean("bootstrap", false));
        apiKeyEntity.setSession(document.getBoolean("session", false));
        apiKeyEntity.setExpiresAt(document.getDate("expires_at"));
        apiKeyEntity.setIdleTimeoutSeconds(document.getInteger("idle_timeout_seconds", 0));
        apiKeyEntity.setIdleExpiresAt(document.getDate("idle_expires_at"));
        apiKeyEntity.setLastUsedAt(document.getDate("last_used_at"));
        apiKeyEntity.setPasswordChangeOnly(document.getBoolean("password_change_only", false));
        apiKeyEntity.setMfaEnrollmentOnly(document.getBoolean("mfa_enrollment_only", false));
        apiKeyEntity.setClientAddress(document.getString("client_address"));
        apiKeyEntity.setUserAgent(document.getString("user_agent"));

        // A key with no scopes recorded can call nothing: scopes are always written at
        // creation, so their absence is a malformed key rather than a legacy one.
        final List<String> scopes = document.getList("scopes", String.class);
        apiKeyEntity.setScopes(scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes));

        return apiKeyEntity;
    }

    public static Document toDocument(final ApiKeyEntity entity) {
        final Document document = new Document();
        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("api_key_hash", entity.getApiKeyHash());
        document.put("api_key_prefix", entity.getApiKeyPrefix());
        document.put("deleted", entity.isDeleted());
        document.put("deleted_at", entity.getDeletedAt());
        document.put("timestamp", entity.getTimestamp());
        document.put("bootstrap", entity.isBootstrap());
        document.put("session", entity.isSession());
        if (entity.isSession()) {
            document.put("expires_at", entity.getExpiresAt());
            document.put("idle_timeout_seconds", entity.getIdleTimeoutSeconds());
            document.put("idle_expires_at", entity.getIdleExpiresAt());
            document.put("last_used_at", entity.getLastUsedAt());
            document.put("password_change_only", entity.isPasswordChangeOnly());
            document.put("mfa_enrollment_only", entity.isMfaEnrollmentOnly());
            if (entity.getClientAddress() != null) {
                document.put("client_address", entity.getClientAddress());
            }
            if (entity.getUserAgent() != null) {
                document.put("user_agent", entity.getUserAgent());
            }
        }
        document.put("scopes", new ArrayList<>(entity.getScopes()));
        return document;
    }

}
