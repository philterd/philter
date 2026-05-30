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
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MongoDBAuditEventPublisherTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    private MongoDBAuditEventPublisher publisher;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("audit_events")).thenReturn(mongoCollection);
        publisher = new MongoDBAuditEventPublisher(mongoClient);
    }

    @Test
    void auditEventInsertsDocumentWithAllFields() {
        final ObjectId apiKeyId = new ObjectId();
        final ObjectId associatedObject = new ObjectId();

        publisher.auditEvent("req-1", AuditLogEvent.POLICY_CREATED, apiKeyId, associatedObject, "10.0.0.1", "details here");

        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(captor.capture());

        final Document document = captor.getValue();
        assertEquals("req-1", document.getString("request_id"));
        assertEquals("policy_created", document.getString("event"));
        assertEquals(apiKeyId, document.getObjectId("api_key_id"));
        assertEquals(associatedObject, document.getObjectId("associated_object"));
        assertEquals("10.0.0.1", document.getString("client_ip_address"));
        assertEquals("details here", document.getString("details"));
        assertNotNull(document.getDate("timestamp"));
    }

    @Test
    void shortOverloadsLeaveOptionalFieldsNull() {
        publisher.auditEvent("req-2", AuditLogEvent.API_KEY_CREATED, new ObjectId());

        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(captor.capture());

        final Document document = captor.getValue();
        assertEquals("api_key_created", document.getString("event"));
        assertEquals(null, document.get("associated_object"));
        assertEquals(null, document.get("client_ip_address"));
        assertEquals(null, document.get("details"));
        assertNotNull(document.getDate("timestamp"));
    }

    @Test
    void publishAuditEventStampsTimestampWhenAbsent() {
        final Map<String, Object> event = new HashMap<>();
        event.put("event", "custom");

        publisher.publishAuditEvent(event);

        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(captor.capture());
        assertNotNull(captor.getValue().getDate("timestamp"));
    }

    @Test
    void insertFailureIsSwallowed() {
        doThrow(new RuntimeException("mongo down")).when(mongoCollection).insertOne(any(Document.class));

        // Must not propagate: auditing cannot break the audited operation.
        publisher.auditEvent("req-3", AuditLogEvent.REDACTION_LEDGER_DELETED, new ObjectId());

        verify(mongoCollection).insertOne(any(Document.class));
    }

}
