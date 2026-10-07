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

    // Disambiguation scopes. "Document" disambiguates within each document (in-memory vectors);
    // "Context" shares disambiguation vectors across the whole context (persisted in MongoDB).
    public static final String DISAMBIGUATION_SCOPE_DOCUMENT = "Document";
    public static final String DISAMBIGUATION_SCOPE_CONTEXT = "Context";

    /** The disambiguation scope values the API accepts, worded for an error message and the documentation. */
    public static final String DISAMBIGUATION_SCOPE_VALUES = "document or context";

    /**
     * The stored disambiguation scope for an API value, {@code document} or {@code context} in any case, or
     * null if the value is neither.
     */
    public static String disambiguationScopeOf(final String value) {
        if (DISAMBIGUATION_SCOPE_DOCUMENT.equalsIgnoreCase(value)) {
            return DISAMBIGUATION_SCOPE_DOCUMENT;
        }
        if (DISAMBIGUATION_SCOPE_CONTEXT.equalsIgnoreCase(value)) {
            return DISAMBIGUATION_SCOPE_CONTEXT;
        }
        return null;
    }

    private ObjectId id;
    private String contextName;
    private int maxSize;
    private boolean disambiguation;
    private String disambiguationScope = DISAMBIGUATION_SCOPE_DOCUMENT;
    private boolean ledger;
    // Which of the user's numbered slots this context holds. A unique index on (user_id, slot) is what
    // keeps a user to MAXIMUM_CONTEXTS_PER_USER contexts, however many creates race.
    private Integer slot;
    private int ttlInHours = DEFAULT_TTL_IN_HOURS;
    private Date timestamp;
    private ObjectId userId;

    public static ContextEntity fromDocument(final Document document) {
        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.id = document.getObjectId("_id");
        contextEntity.userId = document.getObjectId("user_id");
        contextEntity.contextName = document.getString("context_name");
        contextEntity.maxSize = document.getInteger("max_size", MAX_CONTEXT_SIZE);
        contextEntity.disambiguation = document.getBoolean("disambiguation", false);
        contextEntity.disambiguationScope = document.getString("disambiguation_scope") != null
                ? document.getString("disambiguation_scope") : DISAMBIGUATION_SCOPE_DOCUMENT;
        contextEntity.ledger = document.getBoolean("ledger", false);
        contextEntity.slot = document.getInteger("slot");
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
        document.put("disambiguation", disambiguation);
        document.put("disambiguation_scope", disambiguationScope);
        document.put("ledger", ledger);
        document.put("slot", slot);
        document.put("ttl_in_hours", ttlInHours);
        document.put("timestamp", timestamp);
        return document;
    }

    public Integer getSlot() {
        return slot;
    }

    public void setSlot(final Integer slot) {
        this.slot = slot;
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

    public boolean isDisambiguation() {
        return disambiguation;
    }

    public void setDisambiguation(boolean disambiguation) {
        this.disambiguation = disambiguation;
    }

    public String getDisambiguationScope() {
        return disambiguationScope;
    }

    public void setDisambiguationScope(String disambiguationScope) {
        this.disambiguationScope = disambiguationScope;
    }

    public boolean isLedger() {
        return ledger;
    }

    public void setLedger(boolean ledger) {
        this.ledger = ledger;
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

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public ObjectId getUserId() {
        return userId;
    }

}
