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

public class WebhookDeliveryEntity extends AbstractEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String EVENT_DOCUMENT_REDACTION_COMPLETE = "DOCUMENT_REDACTION_COMPLETE";
    public static final String EVENT_DOCUMENT_REDACTION_FAILED = "DOCUMENT_REDACTION_FAILED";

    private ObjectId id;
    private ObjectId userId;
    private String documentId;
    private String eventType;
    private String status;
    private String url;
    private String secret;
    private String payload;
    private int attempts;
    private String lastError;
    private Date nextAttemptAt;
    private Date createdAt;
    private Date updatedAt;
    private Date deliveredAt;

    public static WebhookDeliveryEntity fromDocument(final Document document) {
        final WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setId(document.getObjectId("_id"));
        entity.setUserId(document.getObjectId("user_id"));
        entity.setDocumentId(document.getString("document_id"));
        entity.setEventType(document.getString("event_type"));
        entity.setStatus(document.getString("status"));
        entity.setUrl(document.getString("url"));
        entity.setSecret(document.getString("secret"));
        entity.setPayload(document.getString("payload"));
        entity.setAttempts(document.getInteger("attempts", 0));
        entity.setLastError(document.getString("last_error"));
        entity.setNextAttemptAt(document.getDate("next_attempt_at"));
        entity.setCreatedAt(document.getDate("created_at"));
        entity.setUpdatedAt(document.getDate("updated_at"));
        entity.setDeliveredAt(document.getDate("delivered_at"));
        return entity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("document_id", documentId);
        document.put("event_type", eventType);
        document.put("status", status);
        document.put("url", url);
        document.put("secret", secret);
        document.put("payload", payload);
        document.put("attempts", attempts);
        document.put("last_error", lastError);
        document.put("next_attempt_at", nextAttemptAt);
        document.put("created_at", createdAt);
        document.put("updated_at", updatedAt);
        document.put("delivered_at", deliveredAt);
        return document;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(final ObjectId id) {
        this.id = id;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public void setUserId(final ObjectId userId) {
        this.userId = userId;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(final String documentId) {
        this.documentId = documentId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(final String eventType) {
        this.eventType = eventType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        this.status = status;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(final String url) {
        this.url = url;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(final String secret) {
        this.secret = secret;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(final String payload) {
        this.payload = payload;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(final int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(final String lastError) {
        this.lastError = lastError;
    }

    public Date getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(final Date nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Date createdAt) {
        this.createdAt = createdAt;
    }

    public Date getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(final Date updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Date getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(final Date deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

}
