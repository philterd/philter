package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AbstractEncryptedEntity;
import ai.philterd.philter.services.encryption.EncryptionService;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AbstractEncryptedServiceTest {

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

    private AbstractEncryptedService<TestEncryptedEntity> abstractEncryptedService;

    private static class TestEncryptedEntity extends AbstractEncryptedEntity {
        private ObjectId id;
        @Override
        public ObjectId getId() { return id; }
        public void setId(ObjectId id) { this.id = id; }
        @Override
        public Document toDocument(EncryptionService encryptionService) { return new Document("test", "test"); }
    }

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection(anyString())).thenReturn(mongoCollection);
        abstractEncryptedService = new AbstractEncryptedService<>(mongoClient, "testCollection", encryptionService, auditEventPublisher);
    }

    @Test
    void save() {
        TestEncryptedEntity entity = new TestEncryptedEntity();
        ObjectId id = new ObjectId();
        InsertOneResult insertOneResult = mock(InsertOneResult.class);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertOneResult);
        when(insertOneResult.getInsertedId()).thenReturn(new BsonObjectId(id));

        ObjectId savedId = abstractEncryptedService.save(entity);

        assertEquals(id, savedId);
        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void update() {
        TestEncryptedEntity entity = new TestEncryptedEntity();
        ObjectId id = new ObjectId();
        entity.setId(id);
        
        UpdateResult updateResult = mock(UpdateResult.class);
        when(mongoCollection.updateOne(any(Bson.class), any(Document.class))).thenReturn(updateResult);

        abstractEncryptedService.update(entity);

        verify(mongoCollection).updateOne(any(Bson.class), any(Document.class));
    }
}
