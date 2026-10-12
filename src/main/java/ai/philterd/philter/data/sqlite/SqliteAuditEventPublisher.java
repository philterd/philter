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
package ai.philterd.philter.data.sqlite;

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.audit.StoredAuditEventPublisher;
import ai.philterd.philter.data.RecordIds;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.Map;

/** Writes audit events to SQLite. A failure is logged and never breaks the operation being audited. */
public class SqliteAuditEventPublisher extends StoredAuditEventPublisher {

    private static final Logger LOGGER = LogManager.getLogger(SqliteAuditEventPublisher.class);

    private final SqliteDatabase database;

    public SqliteAuditEventPublisher(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public void publishAuditEvent(final Map<String, Object> auditEvent) {
        final Object timestamp = auditEvent.get("timestamp");
        store(new AuditLogService.AuditEvent(timestamp instanceof Date date ? date : new Date(),
                text(auditEvent.get("event")), text(auditEvent.get("request_id")), text(auditEvent.get("api_key_id")),
                text(auditEvent.get("associated_object")), text(auditEvent.get("client_ip_address")),
                text(auditEvent.get("source")), text(auditEvent.get("details"))));
    }

    private static String text(final Object value) {
        return value == null ? null : value.toString();
    }

    @Override
    protected void store(final AuditLogService.AuditEvent event) {
        try {
            database.write(connection -> Sql.update(connection,
                    "INSERT INTO audit_events (id, timestamp, event, request_id, api_key_id, associated_object, "
                            + "client_ip_address, source, details) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    RecordIds.newId(), event.timestamp(), event.event(), event.requestId(), event.apiKeyId(),
                    event.associatedObject(), event.clientIpAddress(), event.source(), event.details()));
        } catch (final Exception ex) {
            // Auditing must never break the operation being audited.
            LOGGER.error("Unable to persist audit event: {}", event, ex);
        }
    }

}
