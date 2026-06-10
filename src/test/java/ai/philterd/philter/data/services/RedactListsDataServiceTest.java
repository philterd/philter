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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedactListsDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private RedactListsDataService redactListsDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("redact_lists")).thenReturn(mongoCollection);
        redactListsDataService = new RedactListsDataService(mongoClient, new TestEncryptionService(), auditEventPublisher);
    }

    @Test
    void find() {
        ObjectId userId = new ObjectId();
        Document doc = new Document("_id", new ObjectId()).append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        RedactListsEntity result = redactListsDataService.find(userId);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
    }

    @Test
    void findNotFound() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        RedactListsEntity result = redactListsDataService.find(userId);

        assertNull(result);
    }

    @Test
    void saveOrUpdateNew() {
        ObjectId userId = new ObjectId();
        List<String> always = Arrays.asList("a", "b");
        List<String> never = Arrays.asList("c", "d");

        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        redactListsDataService.saveOrUpdate("req", userId, always, never, "source");

        verify(mongoCollection).insertOne(any(Document.class));
        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.REDACT_LISTS_UPDATED),
                eq(userId), eq(userId), eq("source"), any());
    }

    @Test
    void saveOrUpdateExisting() {
        ObjectId userId = new ObjectId();
        List<String> always = Arrays.asList("a", "b");
        List<String> never = Arrays.asList("c", "d");

        Document doc = new Document("_id", new ObjectId()).append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        redactListsDataService.saveOrUpdate("req", userId, always, never, "source");

        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
        verify(auditEventPublisher).auditEvent(eq("req"), eq(ai.philterd.philter.model.AuditLogEvent.REDACT_LISTS_UPDATED),
                eq(userId), eq(userId), eq("source"), any());
    }
}
