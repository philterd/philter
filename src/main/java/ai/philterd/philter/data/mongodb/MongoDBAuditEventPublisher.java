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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.audit.StoredAuditEventPublisher;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Indexes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;

import java.util.Date;
import java.util.Map;

/**
 * An audit event publisher that persists audit events to a MongoDB collection so that
 * security-relevant actions (redactions, ledger queries and deletions, policy and key changes, and
 * so on) are recorded for later review. A failure to write an audit event is logged but never
 * propagated to the caller, so auditing cannot break the operation being audited.
 */
public class MongoDBAuditEventPublisher extends StoredAuditEventPublisher {

    private static final Logger LOGGER = LogManager.getLogger(MongoDBAuditEventPublisher.class);

    private static final String DATABASE = "philter";
    private static final String COLLECTION = "audit_events";

    private final MongoCollection<Document> collection;

    public MongoDBAuditEventPublisher(final MongoClient mongoClient) {
        final MongoDatabase mongoDatabase = mongoClient.getDatabase(DATABASE);
        this.collection = mongoDatabase.getCollection(COLLECTION);

        // The audit log is reviewed by recency and filtered by event/IP. Index creation is
        // idempotent and must never prevent auditing (or startup) from working.
        try {
            collection.createIndex(Indexes.descending("timestamp"));
            // The CSV export pages in (timestamp, _id) order; without this it would sort in memory.
            collection.createIndex(Indexes.descending("timestamp", "_id"));
            collection.createIndex(Indexes.ascending("event", "timestamp"));
        } catch (final Exception ex) {
            LOGGER.warn("Unable to create indexes on the audit_events collection: {}", ex.getMessage());
        }
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
    protected void store(final AuditLogService.AuditEvent event) {
        // The fields in the order and types the audit log has always been stored with.
        publishAuditEvent(new Document()
                .append("request_id", event.requestId())
                .append("event", event.event())
                .append("api_key_id", MongoIds.objectId(event.apiKeyId()))
                .append("associated_object", MongoIds.objectId(event.associatedObject()))
                .append("client_ip_address", event.clientIpAddress())
                .append("source", event.source())
                .append("details", event.details())
                .append("timestamp", event.timestamp()));
    }

}
