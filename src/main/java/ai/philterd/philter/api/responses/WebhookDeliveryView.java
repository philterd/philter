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

import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/** One webhook delivery and how it went. The secret and payload are not included. */
public class WebhookDeliveryView {

    private final String id;
    private final String documentId;
    private final String event;
    private final String url;
    private final String status;
    private final int attempts;
    private final String lastError;
    private final Date createdAt;
    private final Date updatedAt;
    private final Date nextAttemptAt;
    private final Date deliveredAt;

    public WebhookDeliveryView(final WebhookDeliveryEntity delivery) {
        this.id = delivery.getId() == null ? null : delivery.getId().toHexString();
        this.documentId = delivery.getDocumentId();
        this.event = delivery.getEventType();
        this.url = delivery.getUrl();
        this.status = delivery.getStatus();
        this.attempts = delivery.getAttempts();
        this.lastError = delivery.getLastError();
        this.createdAt = delivery.getCreatedAt();
        this.updatedAt = delivery.getUpdatedAt();
        this.nextAttemptAt = delivery.getNextAttemptAt();
        this.deliveredAt = delivery.getDeliveredAt();
    }

    @Schema(description = "The delivery's id, sent as X-Philter-Delivery-Id with each attempt.")
    public String getId() { return id; }

    @Schema(description = "The document the event is about.")
    public String getDocumentId() { return documentId; }

    @Schema(description = "The event: DOCUMENT_REDACTION_COMPLETE or DOCUMENT_REDACTION_FAILED.")
    public String getEvent() { return event; }

    @Schema(description = "Where the delivery is sent: the webhook URL when the event was queued.")
    public String getUrl() { return url; }

    @Schema(description = "PENDING (waiting for its next attempt), PROCESSING (being sent), DELIVERED, or FAILED "
            + "(every attempt failed).", allowableValues = {WebhookDeliveryEntity.STATUS_PENDING,
            WebhookDeliveryEntity.STATUS_PROCESSING, WebhookDeliveryEntity.STATUS_DELIVERED, WebhookDeliveryEntity.STATUS_FAILED})
    public String getStatus() { return status; }

    @Schema(description = "How many attempts have been made.")
    public int getAttempts() { return attempts; }

    @Schema(description = "Why the most recent attempt failed, or null.")
    public String getLastError() { return lastError; }

    @Schema(description = "When the event was queued.")
    public Date getCreatedAt() { return createdAt; }

    @Schema(description = "When the delivery last changed.")
    public Date getUpdatedAt() { return updatedAt; }

    @Schema(description = "When the next attempt is due, for a delivery that is PENDING.")
    public Date getNextAttemptAt() { return nextAttemptAt; }

    @Schema(description = "When the receiver accepted the delivery.")
    public Date getDeliveredAt() { return deliveredAt; }

}
