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
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomListDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private CustomListDataService customListDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("custom_lists")).thenReturn(mongoCollection);
        customListDataService = new CustomListDataService(mongoClient, encryptionService, auditEventPublisher);
    }

    @Test
    void findOneByName() {
        ObjectId userId = new ObjectId();
        String name = "testList";
        Document doc = new Document("_id", new ObjectId())
                .append("name", name)
                .append("user_id", userId);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        CustomListEntity result = customListDataService.findOneByName(name, userId);

        assertNotNull(result);
        assertEquals(name, result.getName());
    }

    @Test
    void existsForUser() {
        ObjectId userId = new ObjectId();
        String name = "testList";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document());

        assertTrue(customListDataService.existsForUser(name, userId));
    }

    @Test
    void deleteByName() {
        ObjectId userId = new ObjectId();
        String name = "testList";

        customListDataService.deleteByName(name, userId);

        verify(mongoCollection).deleteOne(any(Bson.class));
    }

    @Test
    void deleteAll() {
        ObjectId userId = new ObjectId();
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);
        when(deleteResult.getDeletedCount()).thenReturn(5L);

        long deleted = customListDataService.deleteAll(userId);

        assertEquals(5L, deleted);
        verify(mongoCollection).deleteMany(any(Bson.class));
    }

    @Test
    void findAll() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<CustomListEntity> results = customListDataService.findAll(userId, 0, 10);

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
