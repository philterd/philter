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
import ai.philterd.philter.data.entities.ContextEntryEntity;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextEntryDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private ContextEntryDataService contextEntryDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("context_entries")).thenReturn(mongoCollection);
        contextEntryDataService = new ContextEntryDataService(mongoClient, auditEventPublisher);
    }

    @Test
    void findAllByUserIdAndContext() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<ContextEntryEntity> results = contextEntryDataService.findAllByUserIdAndContext(userId, contextName, 10);

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(mongoCollection).find(any(Document.class));
    }

    @Test
    void containsToken() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String token = "sensitive";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document());

        boolean contains = contextEntryDataService.containsToken(userId, contextName, token);

        assertTrue(contains);
    }

    @Test
    void containsReplacement() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String replacement = "redacted";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document());

        boolean contains = contextEntryDataService.containsReplacement(userId, contextName, replacement);

        assertTrue(contains);
    }

    @Test
    void getReplacement() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String token = "sensitive";
        String replacement = "redacted";
        Document doc = new Document("_id", new ObjectId())
                .append("user_id", userId)
                .append("context_name", contextName)
                .append("replacement", replacement)
                .append("reads", 0L);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        String result = contextEntryDataService.getReplacement(userId, contextName, token);

        assertEquals(replacement, result);
        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void putReplacement() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String token = "sensitive";
        String replacement = "redacted";
        String filterType = "type";

        // Mock containsToken to return false
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        contextEntryDataService.putReplacement(userId, contextName, token, replacement, filterType);

        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void deleteByContextName() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";

        contextEntryDataService.deleteByContextName(contextName, userId);

        verify(mongoCollection).deleteMany(any(Document.class));
    }

    @Test
    void deleteByContextNameDefault() {
        ObjectId userId = new ObjectId();
        String contextName = "default";

        contextEntryDataService.deleteByContextName(contextName, userId);

        verify(mongoCollection, never()).deleteMany(any(Document.class));
    }

    @Test
    void getFilterTypeCounts() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        AggregateIterable<Document> aggregateIterable = mock(AggregateIterable.class);
        when(mongoCollection.aggregate(anyList())).thenReturn(aggregateIterable);
        Document resultDoc = new Document("_id", "type1").append("count", 5);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(aggregateIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(resultDoc);

        Map<String, Long> counts = contextEntryDataService.getFilterTypeCounts(contextName, userId);

        assertNotNull(counts);
        assertEquals(1, counts.size());
        assertEquals(5L, counts.get("type1"));
    }

    @Test
    void saveThrowsException() {
        assertThrows(UnsupportedOperationException.class, () -> contextEntryDataService.save(new ContextEntryEntity()));
    }
}
