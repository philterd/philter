package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ChangeSetEntity;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
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
class ChangeSetDataServiceTest {

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

    private ChangeSetDataService changeSetDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("change_sets")).thenReturn(mongoCollection);
        changeSetDataService = new ChangeSetDataService(mongoClient, encryptionService, auditEventPublisher);
    }

    @Test
    void getCurrentVersion() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        Document doc = new Document("version", 5);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(doc);

        int version = changeSetDataService.getCurrentVersion(userId, documentId);

        assertEquals(5, version);
    }

    @Test
    void getCurrentVersionNotFound() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        int version = changeSetDataService.getCurrentVersion(userId, documentId);

        assertEquals(1, version);
    }

    @Test
    void deleteByUserIdAndDocumentId() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";

        changeSetDataService.deleteByUserIdAndDocumentId(userId, documentId);

        verify(mongoCollection).deleteMany(any(Bson.class));
    }

    @Test
    void getChangeSetVersionsForDocument() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new Document("version", 1));

        List<Integer> versions = changeSetDataService.getChangeSetVersionsForDocument(userId, documentId);

        assertEquals(1, versions.size());
        assertEquals(1, versions.get(0));
    }

    @Test
    void count() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        int version = 1;
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(10L);

        int count = changeSetDataService.count(userId, documentId, version);

        assertEquals(10, count);
    }
}
