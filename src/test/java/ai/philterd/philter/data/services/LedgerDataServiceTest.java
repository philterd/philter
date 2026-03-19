package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.LedgerEntity;
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
class LedgerDataServiceTest {

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

    private LedgerDataService ledgerDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("ledger")).thenReturn(mongoCollection);
        ledgerDataService = new LedgerDataService(mongoClient, encryptionService, auditEventPublisher);
    }

    @Test
    void isDocumentIdUnique() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(1)).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        assertTrue(ledgerDataService.isDocumentIdUnique(userId, documentId));
    }

    @Test
    void getLatestTransaction() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        Document doc = new Document("_id", new ObjectId())
                .append("document_id", documentId)
                .append("user_id", userId)
                .append("start_position", 0L);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(1)).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        LedgerEntity result = ledgerDataService.getLatestTransaction(userId, documentId);

        assertNotNull(result);
        assertEquals(documentId, result.getDocumentId());
    }

    @Test
    void deleteAllByUserId() {
        ObjectId userId = new ObjectId();
        DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);
        when(deleteResult.getDeletedCount()).thenReturn(10L);

        long deleted = ledgerDataService.deleteAllByUserId(userId);

        assertEquals(10L, deleted);
        verify(mongoCollection).deleteMany(any(Bson.class));
    }

    @Test
    void countChainsByUserId() {
        ObjectId userId = new ObjectId();
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(5L);

        int count = ledgerDataService.countChainsByUserId(userId);

        assertEquals(5, count);
    }

    @Test
    void findChainsByUserId() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<LedgerEntity> results = ledgerDataService.findChainsByUserId("req", userId, 0, 10, "source");

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }
}
