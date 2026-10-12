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

import java.util.Date;

public class WebhookDeliveryEntity extends AbstractEncryptedEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String EVENT_DOCUMENT_REDACTION_COMPLETE = "DOCUMENT_REDACTION_COMPLETE";
    public static final String EVENT_DOCUMENT_REDACTION_FAILED = "DOCUMENT_REDACTION_FAILED";

    /** A test event sent on request to check a webhook, never queued or retried. */
    public static final String EVENT_WEBHOOK_TEST = "WEBHOOK_TEST";

    private String id;
    private String userId;
    private String documentId;
    private String eventType;
    private String status;
    private String url;
    private String secret;
    private String payload;
    private int attempts;
    private String claimToken;
    private Date claimExpiresAt;
    private String lastError;
    private Date nextAttemptAt;
    private Date createdAt;
    private Date updatedAt;
    private Date deliveredAt;
    private Date completedAt;
    public Date getCompletedAt() { return completedAt; }
    public void setCompletedAt(Date value) { completedAt = value; }

    @Override
    public String getId() {
        return id;
    }

    public void setId(final String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(final String userId) {
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

    public String getClaimToken() {
        return claimToken;
    }

    public void setClaimToken(final String claimToken) {
        this.claimToken = claimToken;
    }

    public Date getClaimExpiresAt() {
        return claimExpiresAt;
    }

    public void setClaimExpiresAt(final Date claimExpiresAt) {
        this.claimExpiresAt = claimExpiresAt;
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
