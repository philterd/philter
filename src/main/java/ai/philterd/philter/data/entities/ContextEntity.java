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

public class ContextEntity extends AbstractEntity {

    public static final int MAX_CONTEXT_SIZE = 10000;
    public static final int DEFAULT_TTL_IN_HOURS = 48;

    private ObjectId id;
    private String contextName;
    private int maxSize;
    private boolean coref;
    private boolean disambiguation;
    private int ttlInHours = DEFAULT_TTL_IN_HOURS;
    private Date timestamp;

    public static ContextEntity fromDocument(final Document document) {
        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.id = document.getObjectId("_id");
        contextEntity.userId = document.getObjectId("user_id");
        contextEntity.contextName = document.getString("context_name");
        contextEntity.maxSize = document.getInteger("max_size", MAX_CONTEXT_SIZE);
        contextEntity.coref = document.getBoolean("coref", false);
        contextEntity.disambiguation = document.getBoolean("disambiguation", false);
        contextEntity.ttlInHours = document.getInteger("ttl_in_hours", DEFAULT_TTL_IN_HOURS);
        contextEntity.timestamp = document.getDate("timestamp");
        return contextEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("context_name", contextName);
        document.put("max_size", maxSize);
        document.put("coref", coref);
        document.put("disambiguation", disambiguation);
        document.put("ttl_in_hours", ttlInHours);
        document.put("timestamp", timestamp);
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

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public boolean isCoref() {
        return coref;
    }

    public void setCoref(boolean coref) {
        this.coref = coref;
    }

    public boolean isDisambiguation() {
        return disambiguation;
    }

    public void setDisambiguation(boolean disambiguation) {
        this.disambiguation = disambiguation;
    }

    public int getTtlInHours() {
        return ttlInHours;
    }

    public void setTtlInHours(int ttlInHours) {
        this.ttlInHours = ttlInHours;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }
}
