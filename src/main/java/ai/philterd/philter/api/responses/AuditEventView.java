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

import java.util.Date;

/**
 * A single audit event as returned by the audit API. Ids are rendered as strings so a client need not
 * know the storage representation. Audit events never carry the sensitive values themselves.
 */
public class AuditEventView {

    private final Date timestamp;
    private final String event;
    private final String requestId;
    private final String apiKeyId;
    private final String associatedObject;
    private final String clientIpAddress;
    private final String details;

    public AuditEventView(final Date timestamp, final String event, final String requestId, final String apiKeyId,
                          final String associatedObject, final String clientIpAddress, final String details) {
        this.timestamp = timestamp;
        this.event = event;
        this.requestId = requestId;
        this.apiKeyId = apiKeyId;
        this.associatedObject = associatedObject;
        this.clientIpAddress = clientIpAddress;
        this.details = details;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public String getEvent() {
        return event;
    }

    public String getRequestId() {
        return requestId;
    }

    /** The acting principal, when one was recorded. */
    public String getApiKeyId() {
        return apiKeyId;
    }

    /** The entity the action concerned, when applicable. */
    public String getAssociatedObject() {
        return associatedObject;
    }

    public String getClientIpAddress() {
        return clientIpAddress;
    }

    public String getDetails() {
        return details;
    }

}
