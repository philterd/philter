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
import ai.philterd.philter.data.entities.GlobalTermsEntity;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalTermsDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private GlobalTermsDataService globalTermsDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("global_terms")).thenReturn(mongoCollection);
        globalTermsDataService = new GlobalTermsDataService(mongoClient, auditEventPublisher);
    }

    @Test
    void find() {
        ObjectId userId = new ObjectId();
        Document doc = new Document("_id", new ObjectId()).append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        GlobalTermsEntity result = globalTermsDataService.find(userId);

        assertNotNull(result);
        assertEquals(userId, result.getUserId());
    }

    @Test
    void findNotFound() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        GlobalTermsEntity result = globalTermsDataService.find(userId);

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

        globalTermsDataService.saveOrUpdate(userId, always, never);

        verify(mongoCollection).insertOne(any(Document.class));
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

        globalTermsDataService.saveOrUpdate(userId, always, never);

        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }
}
