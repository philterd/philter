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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

    private Document recorded() {
        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(captor.capture());
        return captor.getValue();
    }

    private static void serving(final String remoteAddress) {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
        ClientAddress.clear();
    }

    @Test
    void aSourceWithoutARequestIsRecordedAsTheSourceAndNoAddress() {
        publisher.auditEvent("req-3", AuditLogEvent.USER_CREATED, new ObjectId(), new ObjectId(), "system", null);

        final Document document = recorded();
        assertNull(document.get("client_ip_address"));
        assertEquals("system", document.getString("source"));
    }

    @Test
    void anAddressWithoutARequestIsRecordedAsTheAddressFromTheApi() {
        publisher.auditEvent("req-4", AuditLogEvent.SIGN_IN_FAILED, null, null, "2001:db8::7", null);

        final Document document = recorded();
        assertEquals("2001:db8::7", document.getString("client_ip_address"));
        assertEquals("api", document.getString("source"));
    }

    @Test
    void nothingWithoutARequestIsASystemEvent() {
        publisher.auditEvent(null, AuditLogEvent.SIGNING_KEY_GENERATED, null, null, null, null);

        final Document document = recorded();
        assertNull(document.get("client_ip_address"));
        assertEquals("system", document.getString("source"));
    }

    @Test
    void theRequestsAddressIsRecordedWhateverTheCallerPasses() {
        serving("198.51.100.20");

        publisher.auditEvent("req-5", AuditLogEvent.POLICY_CREATED, new ObjectId(), null, "api", null);
        publisher.auditEvent("req-6", AuditLogEvent.SETTINGS_UPDATED, new ObjectId(), null, null, null);
        publisher.auditEvent("req-7", AuditLogEvent.SIGN_IN_FAILED, null, null, "203.0.113.9", null);
        // A session key a request presents expires as the request is handled: the request caused it.
        publisher.auditEvent("req-8", AuditLogEvent.API_KEY_EXPIRED, new ObjectId(), null, "system", null);

        final ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection, times(4)).insertOne(captor.capture());
        for (final Document document : captor.getAllValues()) {
            assertEquals("198.51.100.20", document.getString("client_ip_address"), document.toJson());
            assertEquals("api", document.getString("source"), document.toJson());
        }
    }

    @Test
    void workARequestSubmittedRecordsThatRequestsAddress() {
        ClientAddress.setSubmittedBy("198.51.100.30");

        publisher.auditEvent("doc-1", AuditLogEvent.DOCUMENT_REDACTION_COMPLETED, new ObjectId(), null, null, "redactions: 1");

        final Document document = recorded();
        assertEquals("198.51.100.30", document.getString("client_ip_address"));
        assertEquals("api", document.getString("source"));
    }

    @Test
    void aSubmitterValueThatIsNotAnAddressIsIgnored() {
        ClientAddress.setSubmittedBy("api");

        publisher.auditEvent("doc-2", AuditLogEvent.DOCUMENT_REDACTION_COMPLETED, new ObjectId(), null, null, null);

        final Document document = recorded();
        assertNull(document.get("client_ip_address"));
        assertEquals("system", document.getString("source"));
    }

}
