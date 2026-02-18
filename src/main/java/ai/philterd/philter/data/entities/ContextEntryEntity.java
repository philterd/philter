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

public class ContextEntryEntity extends AbstractEntity {

    private ObjectId id;
    private ObjectId userId;
    private String contextName;
    private String tokenHash;
    private String replacement;
    private boolean replacementUuid;
    private long reads;
    private Date timestamp;
    private String filterType;

    public static ContextEntryEntity fromDocument(final Document document) {
        final ContextEntryEntity contextEntryEntity = new ContextEntryEntity();
        contextEntryEntity.id = document.getObjectId("_id");
        contextEntryEntity.userId = document.getObjectId("user_id");
        contextEntryEntity.contextName = document.getString("context_name");
        contextEntryEntity.tokenHash = document.getString("token_hash");
        contextEntryEntity.replacement = document.getString("replacement");
        contextEntryEntity.replacementUuid = document.getBoolean("replacement_uuid", false);
        contextEntryEntity.reads = document.getLong("reads");
        contextEntryEntity.timestamp = document.getDate("timestamp");
        contextEntryEntity.filterType = document.getString("filter_type");
        return contextEntryEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("context_name", contextName);
        document.put("token_hash", tokenHash);
        document.put("replacement", replacement);
        document.put("replacement_uuid", replacementUuid);
        document.put("reads", reads);
        document.put("timestamp", timestamp);
        document.put("filter_type", filterType);
        return document;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getContextName() {
        return contextName;
    }

    public void setContextName(String contextName) {
        this.contextName = contextName;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    public long getReads() {
        return reads;
    }

    public void setReads(long reads) {
        this.reads = reads;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getFilterType() {
        return filterType;
    }

    public void setFilterType(String filterType) {
        this.filterType = filterType;
    }

    public boolean isReplacementUuid() {
        return replacementUuid;
    }

    public void setReplacementUuid(boolean replacementUuid) {
        this.replacementUuid = replacementUuid;
    }

}