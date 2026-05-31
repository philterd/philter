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
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ContextCache;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import org.bson.BsonObjectId;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private ContextCache contextCache;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private ContextDataService contextDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(mongoCollection);
        contextDataService = new ContextDataService(mongoClient, contextCache, auditEventPublisher);
    }

    @Test
    void create() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        
        // Mock findAll(userId).size() check and findOne
        FindIterable<Document> findAllIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findAllIterable);
        when(findAllIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));
        when(findAllIterable.first()).thenReturn(null);

        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        ServiceResponse response = contextDataService.create(contextName, userId, false, false);

        assertTrue(response.isSuccessful());
        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void createPersistsLedgerFlag() {
        final ObjectId userId = new ObjectId();
        final String contextName = "testContext";

        final FindIterable<Document> findAllIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findAllIterable);
        when(findAllIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));
        when(findAllIterable.first()).thenReturn(null);

        final InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        contextDataService.create(contextName, userId, false, true);

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(docCaptor.capture());
        assertEquals(Boolean.TRUE, docCaptor.getValue().getBoolean("ledger"),
                "the per-context ledger flag must be persisted on the stored context");
    }

    @Test
    void createDefaultsLedgerFlagOff() {
        final ObjectId userId = new ObjectId();
        final String contextName = "testContext";

        final FindIterable<Document> findAllIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findAllIterable);
        when(findAllIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));
        when(findAllIterable.first()).thenReturn(null);

        final InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));

        // The name-and-user create overload must leave the ledger off by default.
        contextDataService.create(contextName, userId);

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(docCaptor.capture());
        assertEquals(Boolean.FALSE, docCaptor.getValue().getBoolean("ledger"),
                "a context created without specifying the ledger flag must default to off");
    }

    @Test
    void findAll() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));

        List<ContextEntity> contexts = contextDataService.findAll(userId);

        assertNotNull(contexts);
        verify(mongoCollection).find(any(Document.class));
    }

    @Test
    void findOne() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        Document doc = new Document("_id", new ObjectId()).append("context_name", contextName).append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        ContextEntity context = contextDataService.findOne(contextName, userId);

        assertNotNull(context);
        assertEquals(contextName, context.getContextName());
    }

    @Test
    void deleteByName() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        
        // Mock findOne to return the context
        Document doc = new Document("_id", new ObjectId()).append("context_name", contextName).append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteOne(any(Document.class))).thenReturn(deleteResult);
        
        // Mocking the extra deletions in deleteByName (context entries via a Document filter and
        // disambiguation vectors via a Bson filter), so match on Bson to cover both.
        MongoCollection<Document> mockCollection = mock(MongoCollection.class);
        lenient().when(mongoDatabase.getCollection(anyString())).thenReturn(mockCollection);
        lenient().when(mockCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        ServiceResponse response = contextDataService.deleteByName(contextName, userId);

        assertTrue(response.isSuccessful());
        verify(mongoCollection).deleteOne(any(Document.class));
        verify(contextCache).deleteContext(eq(contextName));
    }

    @Test
    void deleteByNameDeletesDisambiguationVectorsForTheContext() {
        final ObjectId userId = new ObjectId();
        final String contextName = "testContext";

        // findOne returns the context so deletion proceeds.
        final Document doc = new Document("_id", new ObjectId()).append("context_name", contextName).append("user_id", userId);
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        final DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteOne(any(Document.class))).thenReturn(deleteResult);
        when(mongoCollection.deleteMany(any(Document.class))).thenReturn(deleteResult);

        // Route the vectors collection to a dedicated mock so the vector cleanup is observable
        // independently of the context and context-entry deletions.
        final MongoCollection<Document> vectorsCollection = mock(MongoCollection.class);
        when(mongoDatabase.getCollection("vectors")).thenReturn(vectorsCollection);
        when(vectorsCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        final ServiceResponse response = contextDataService.deleteByName(contextName, userId);

        assertTrue(response.isSuccessful());
        // The span-disambiguation vectors for the deleted context must also be cleared.
        verify(vectorsCollection).deleteMany(any(Bson.class));
    }

    @Test
    void emptyByNameDeletesDisambiguationVectorsForTheContext() {
        final ObjectId userId = new ObjectId();
        final String contextName = "testContext";

        // findOne returns the context so emptying proceeds.
        final Document doc = new Document("_id", new ObjectId()).append("context_name", contextName).append("user_id", userId);
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        final DeleteResult deleteResult = mock(DeleteResult.class);
        lenient().when(mongoCollection.deleteMany(any(Document.class))).thenReturn(deleteResult);

        // Route the vectors collection to a dedicated mock so the vector cleanup is observable.
        final MongoCollection<Document> vectorsCollection = mock(MongoCollection.class);
        when(mongoDatabase.getCollection("vectors")).thenReturn(vectorsCollection);
        when(vectorsCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        final ServiceResponse response = contextDataService.emptyByName(contextName, userId);

        assertTrue(response.isSuccessful());
        // Emptying a context must also clear its span-disambiguation vectors.
        verify(vectorsCollection).deleteMany(any(Bson.class));
    }

    @Test
    void findOneByNameAndUserIdQueriesContextNameField() {
        ObjectId userId = new ObjectId();
        String contextName = "my-context";

        Document doc = new Document("_id", new ObjectId()).append("context_name", contextName).append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        final ContextEntity context = contextDataService.findOneByNameAndUserId(contextName, userId);

        assertNotNull(context);

        // The query must use the persisted field name `context_name`, not `name`.
        final ArgumentCaptor<Document> queryCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).find(queryCaptor.capture());
        final Document query = queryCaptor.getValue();
        assertEquals(contextName, query.getString("context_name"));
        assertFalse(query.containsKey("name"));
    }
}
