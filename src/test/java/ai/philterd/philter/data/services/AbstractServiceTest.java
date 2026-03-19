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
import ai.philterd.philter.data.entities.AbstractEntity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private AbstractService<TestEntity> abstractService;

    private static class TestEntity extends AbstractEntity {
        private ObjectId id;
        @Override
        public ObjectId getId() { return id; }
        public void setId(ObjectId id) { this.id = id; }
        @Override
        public Document toDocument() { return new Document("test", "test"); }
    }

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(mongoCollection);
        abstractService = new AbstractService<>(mongoClient, "testCollection", auditEventPublisher);
    }

    @Test
    void save() {
        TestEntity entity = new TestEntity();
        ObjectId id = new ObjectId();
        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(id));

        ObjectId savedId = abstractService.save(entity);

        assertEquals(id, savedId);
        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void update() {
        TestEntity entity = new TestEntity();
        ObjectId id = new ObjectId();
        entity.setId(id);
        
        UpdateResult updateResult = mock(UpdateResult.class);
        when(mongoCollection.updateOne(any(Bson.class), any(Document.class))).thenReturn(updateResult);

        abstractService.update(entity);

        verify(mongoCollection).updateOne(any(Bson.class), any(Document.class));
    }
}
