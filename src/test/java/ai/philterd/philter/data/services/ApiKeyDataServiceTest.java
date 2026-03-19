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
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private ApiKeyDataService apiKeyDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("api_keys")).thenReturn(mongoCollection);
        apiKeyDataService = new ApiKeyDataService(mongoClient, auditEventPublisher);
    }

    @Test
    void createApiKey() {
        ObjectId userId = new ObjectId();
        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        ObjectId apiKeyId = new ObjectId();
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(apiKeyId));
        
        // Mocking doesApiKeyExist via mongoCollection.find
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        ServiceResponse response = apiKeyDataService.createApiKey("requestId", userId, "source");

        assertTrue(response.isSuccessful());
        assertNotNull(response.getMessage());
        verify(mongoCollection).insertOne(any(Document.class));
        verify(auditEventPublisher).auditEvent(eq("requestId"), eq(AuditLogEvent.API_KEY_CREATED), eq(apiKeyId), eq("source"));
    }

    @Test
    void findOneByApiKey() {
        String apiKey = "sk_test_key_1234567890123456789012";
        Document doc = new Document("_id", new ObjectId()).append("user_id", new ObjectId()).append("deleted", false);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        ApiKeyEntity entity = apiKeyDataService.findOneByApiKey(apiKey);

        assertNotNull(entity);
        verify(mongoCollection).find(any(Document.class));
    }

    @Test
    void findAll() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(mock(com.mongodb.client.MongoCursor.class));

        List<ApiKeyEntity> entities = apiKeyDataService.findAll(userId, 0, 10);

        assertNotNull(entities);
        assertTrue(entities.isEmpty());
    }

    @Test
    void count() {
        ObjectId userId = new ObjectId();
        when(mongoCollection.countDocuments(any(Document.class))).thenReturn(5L);

        int count = apiKeyDataService.count(userId);

        assertEquals(5, count);
    }

    @Test
    void deleteByApiKey() {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(new ObjectId());
        UpdateResult updateResult = mock(UpdateResult.class);
        when(mongoCollection.updateOne(any(Bson.class), any(Document.class))).thenReturn(updateResult);

        ServiceResponse response = apiKeyDataService.deleteByApiKey("requestId", entity, "source");

        assertTrue(response.isSuccessful());
        assertTrue(entity.isDeleted());
        verify(mongoCollection).updateOne(any(Bson.class), any(Document.class));
        verify(auditEventPublisher).auditEvent(eq("requestId"), eq(AuditLogEvent.API_KEY_DELETED), eq(entity.getId()), eq(entity.getId()), eq("source"));
    }
}
