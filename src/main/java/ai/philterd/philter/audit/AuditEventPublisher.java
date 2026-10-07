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

import ai.philterd.philter.model.AuditLogEvent;
import org.bson.types.ObjectId;

import java.util.Map;

/**
 * Records audit events. Where a method takes {@code clientIpAddress}, it is a client address or, from code
 * with no request behind it, a {@link ai.philterd.philter.model.Source}. While a request is being served,
 * that request's address is recorded whatever is passed; see {@link ClientAddress}. A value that is not an
 * address is recorded as the event's {@code source}, never as its address.
 */
public interface AuditEventPublisher {

    void publishAuditEvent(final Map<String, Object> auditEvent);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final String clientIpAddress);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject, final String clientIpAddress);

    void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject, final String clientIpAddress, final String details);

}
