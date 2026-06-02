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
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for {@link MongoDBAuditEventPublisher} against a real (in-memory) MongoDB. These
 * verify that every {@code auditEvent} overload and {@code publishAuditEvent} actually inserts a
 * document into the {@code audit_events} collection with the expected field names and values, and
 * that the timestamp is populated when absent.
 */
class MongoDBAuditEventPublisherIT extends AbstractMongoIT {

    private MongoDBAuditEventPublisher publisher;
    private MongoCollection<Document> auditEvents;

    @BeforeEach
    void setUpPublisher() {
        publisher = new MongoDBAuditEventPublisher(mongoClient);
        auditEvents = mongoClient.getDatabase("philter").getCollection("audit_events");
    }

    @Test
    void fullAuditEventWritesAllFields() {
        final ObjectId apiKeyId = new ObjectId();
        final ObjectId associatedObject = new ObjectId();

        publisher.auditEvent("req-1", AuditLogEvent.USER_CREATED, apiKeyId, associatedObject, "1.2.3.4", "role: admin");

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals("req-1", stored.getString("request_id"));
        assertEquals(AuditLogEvent.USER_CREATED.getAuditLogEvent(), stored.getString("event"));
        assertEquals(apiKeyId, stored.getObjectId("api_key_id"));
        assertEquals(associatedObject, stored.getObjectId("associated_object"));
        assertEquals("1.2.3.4", stored.getString("client_ip_address"));
        assertEquals("role: admin", stored.getString("details"));
        assertNotNull(stored.getDate("timestamp"));
    }

    @Test
    void apiKeyOnlyOverloadLeavesOptionalFieldsNull() {
        final ObjectId apiKeyId = new ObjectId();

        publisher.auditEvent("req-2", AuditLogEvent.API_KEY_CREATED, apiKeyId);

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals("req-2", stored.getString("request_id"));
        assertEquals(AuditLogEvent.API_KEY_CREATED.getAuditLogEvent(), stored.getString("event"));
        assertEquals(apiKeyId, stored.getObjectId("api_key_id"));
        assertNull(stored.get("associated_object"));
        assertNull(stored.get("client_ip_address"));
        assertNull(stored.get("details"));
        assertNotNull(stored.getDate("timestamp"));
    }

    @Test
    void clientIpOverloadStoresIpAddress() {
        final ObjectId apiKeyId = new ObjectId();

        publisher.auditEvent("req-3", AuditLogEvent.API_AUTHENTICATION_FAILED, apiKeyId, "10.0.0.9");

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals(AuditLogEvent.API_AUTHENTICATION_FAILED.getAuditLogEvent(), stored.getString("event"));
        assertEquals(apiKeyId, stored.getObjectId("api_key_id"));
        assertEquals("10.0.0.9", stored.getString("client_ip_address"));
        assertNull(stored.get("associated_object"));
        assertNull(stored.get("details"));
    }

    @Test
    void associatedObjectOverloadStoresAssociatedObject() {
        final ObjectId apiKeyId = new ObjectId();
        final ObjectId associatedObject = new ObjectId();

        publisher.auditEvent("req-4", AuditLogEvent.POLICY_DELETED, apiKeyId, associatedObject);

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals(AuditLogEvent.POLICY_DELETED.getAuditLogEvent(), stored.getString("event"));
        assertEquals(apiKeyId, stored.getObjectId("api_key_id"));
        assertEquals(associatedObject, stored.getObjectId("associated_object"));
        assertNull(stored.get("client_ip_address"));
        assertNull(stored.get("details"));
    }

    @Test
    void associatedObjectAndClientIpOverloadStoresBoth() {
        final ObjectId apiKeyId = new ObjectId();
        final ObjectId associatedObject = new ObjectId();

        publisher.auditEvent("req-5", AuditLogEvent.LEDGER_DELETED, apiKeyId, associatedObject, "192.168.1.1");

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals(AuditLogEvent.LEDGER_DELETED.getAuditLogEvent(), stored.getString("event"));
        assertEquals(apiKeyId, stored.getObjectId("api_key_id"));
        assertEquals(associatedObject, stored.getObjectId("associated_object"));
        assertEquals("192.168.1.1", stored.getString("client_ip_address"));
        assertNull(stored.get("details"));
    }

    @Test
    void publishAuditEventInsertsSuppliedMap() {
        final Map<String, Object> event = new HashMap<>();
        event.put("request_id", "req-6");
        event.put("event", "custom_event");
        event.put("details", "free-form details");

        publisher.publishAuditEvent(event);

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals("req-6", stored.getString("request_id"));
        assertEquals("custom_event", stored.getString("event"));
        assertEquals("free-form details", stored.getString("details"));
        // The publisher fills in a timestamp when the map does not provide one.
        assertNotNull(stored.getDate("timestamp"));
    }

    @Test
    void publishAuditEventPreservesProvidedTimestamp() {
        final java.util.Date provided = new java.util.Date(0L);
        final Map<String, Object> event = new HashMap<>();
        event.put("event", "explicit_timestamp");
        event.put("timestamp", provided);

        publisher.publishAuditEvent(event);

        final Document stored = auditEvents.find().first();
        assertNotNull(stored);
        assertEquals(provided, stored.getDate("timestamp"));
    }

    @Test
    void eachAuditEventInsertsADistinctDocument() {
        publisher.auditEvent("req-a", AuditLogEvent.USER_CREATED, new ObjectId());
        publisher.auditEvent("req-b", AuditLogEvent.USER_DELETED, new ObjectId());

        assertEquals(2, auditEvents.countDocuments());
    }

}
