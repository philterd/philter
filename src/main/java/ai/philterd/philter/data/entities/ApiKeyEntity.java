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
import org.bson.types.ObjectId;

import java.util.Date;

public class ApiKeyEntity extends AbstractEntity {

    private ObjectId id;
    private String apiKeyHash;
    private String apiKeyPrefix;
    private boolean deleted;
    private Date timestamp;
    private transient String apiKey;

    public static ApiKeyEntity fromDocument(final Document document) {
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setId(document.getObjectId("_id"));

        // Read hash and prefix from document
        apiKeyEntity.setApiKeyHash(document.getString("api_key_hash"));
        apiKeyEntity.setApiKeyPrefix(document.getString("api_key_prefix"));

        apiKeyEntity.setDeleted(document.getBoolean("deleted"));
        apiKeyEntity.setTimestamp(document.getDate("timestamp"));
        return apiKeyEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("api_key_hash", apiKeyHash);
        document.put("api_key_prefix", apiKeyPrefix);
        document.put("deleted", deleted);
        document.put("timestamp", timestamp);
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

}
