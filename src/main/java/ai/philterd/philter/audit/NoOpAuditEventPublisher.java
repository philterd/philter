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

public class NoOpAuditEventPublisher implements AuditEventPublisher {

    @Override
    public void publishAuditEvent(Map<String, Object> auditEvent) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, String clientIpAddress) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, ObjectId associatedObject) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, ObjectId associatedObject, String clientIpAddress) {

    }

    @Override
    public void auditEvent(String requestId, AuditLogEvent auditLogEvent, ObjectId userId, ObjectId associatedObject, String clientIpAddress, String details) {

    }

}
