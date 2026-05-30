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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.Map;

/**
 * An {@link AuditEventPublisher} that persists audit events to a MongoDB collection so that
 * security-relevant actions (redactions, ledger queries and deletions, policy and key changes, and
 * so on) are recorded for later review. A failure to write an audit event is logged but never
 * propagated to the caller, so auditing cannot break the operation being audited.
 */
public class MongoDBAuditEventPublisher implements AuditEventPublisher {

    private static final Logger LOGGER = LogManager.getLogger(MongoDBAuditEventPublisher.class);

    private static final String DATABASE = "philter";
    private static final String COLLECTION = "audit_events";

    private final MongoCollection<Document> collection;

    public MongoDBAuditEventPublisher(final MongoClient mongoClient) {
        final MongoDatabase mongoDatabase = mongoClient.getDatabase(DATABASE);
        this.collection = mongoDatabase.getCollection(COLLECTION);
    }

    @Override
    public void publishAuditEvent(final Map<String, Object> auditEvent) {

        try {
            final Document document = new Document(auditEvent);
            document.putIfAbsent("timestamp", new Date());
            collection.insertOne(document);
        } catch (final Exception ex) {
            // Auditing must never break the operation being audited.
            LOGGER.error("Unable to persist audit event: {}", auditEvent, ex);
        }

    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId) {
        auditEvent(requestId, auditLogEvent, apiKeyId, null, null, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final String clientIpAddress) {
        auditEvent(requestId, auditLogEvent, apiKeyId, null, clientIpAddress, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject) {
        auditEvent(requestId, auditLogEvent, apiKeyId, associatedObject, null, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject, final String clientIpAddress) {
        auditEvent(requestId, auditLogEvent, apiKeyId, associatedObject, clientIpAddress, null);
    }

    @Override
    public void auditEvent(final String requestId, final AuditLogEvent auditLogEvent, final ObjectId apiKeyId, final ObjectId associatedObject, final String clientIpAddress, final String details) {

        final Document document = new Document()
                .append("request_id", requestId)
                .append("event", auditLogEvent == null ? null : auditLogEvent.getAuditLogEvent())
                .append("api_key_id", apiKeyId)
                .append("associated_object", associatedObject)
                .append("client_ip_address", clientIpAddress)
                .append("details", details)
                .append("timestamp", new Date());

        publishAuditEvent(document);

    }

}
