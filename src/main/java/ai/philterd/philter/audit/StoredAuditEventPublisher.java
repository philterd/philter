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
package ai.philterd.philter.audit;

import ai.philterd.philter.config.AuditConfig;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.Source;

import java.util.Date;

/**
 * Applies the audit rules every storage implementation shares, and leaves only storing the event to the
 * implementation: redaction activity is not recorded when redaction auditing is off, and the client address and
 * source come from the request being served. A failure to store an event must never be propagated to the
 * caller, so auditing cannot break the operation being audited.
 */
public abstract class StoredAuditEventPublisher implements AuditEventPublisher {

    /** Stores one event. Must not throw. */
    protected abstract void store(AuditLogService.AuditEvent event);

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final String apiKeyId) {
        auditEvent(requestId, auditLogEvent, apiKeyId, null, null, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final String apiKeyId, final String clientIpAddress) {
        auditEvent(requestId, auditLogEvent, apiKeyId, null, clientIpAddress, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final String apiKeyId, final String associatedObject, final String clientIpAddress) {
        auditEvent(requestId, auditLogEvent, apiKeyId, associatedObject, clientIpAddress, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final String apiKeyId, final String associatedObject, final String clientIpAddress, final String details) {

        // Enforced here, at the single write path, so no caller can bypass it and no future caller
        // has to remember the rule. Security events are never suppressed.
        if (auditLogEvent != null && auditLogEvent.isRedactionActivity()
                && !AuditConfig.isRedactionAuditingEnabled()) {
            return;
        }

        // Callers pass a client address or, from code that has no request, a source such as "system".
        // The request being served decides both, so a caller cannot record a different address and a
        // service called from a request records it without being given it; the field holds only
        // addresses, and where the event came from is the source.
        final boolean givenAnAddress = ClientAddress.isAddress(clientIpAddress);
        final String requestAddress = ClientAddress.current();
        final String address = requestAddress != null ? requestAddress : givenAnAddress ? clientIpAddress : null;
        final String source = requestAddress == null && clientIpAddress != null && !givenAnAddress ? clientIpAddress
                : address != null ? Source.API.getSource() : Source.SYSTEM.getSource();

        store(new AuditLogService.AuditEvent(new Date(), auditLogEvent == null ? null : auditLogEvent.getAuditLogEvent(),
                requestId, apiKeyId, associatedObject, address, source, details));

    }

}
