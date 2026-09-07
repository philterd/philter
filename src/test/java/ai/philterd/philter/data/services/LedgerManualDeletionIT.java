package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerManualDeletionIT extends AbstractMongoIT {
    private final TestEncryptionService encryption = new TestEncryptionService();
    private MongoDatabase isolated;
    private final ObjectId owner = new ObjectId();
    @BeforeEach void realServerWhenRequested() {
        String uri = System.getProperty("philter.test.mongoUri");
        if (uri != null) {
            mongoClient.close(); mongoClient = spy(MongoClients.create(uri));
            isolated = mongoClient.getDatabase("philter_r3_" + new ObjectId());
            doReturn(isolated).when(mongoClient).getDatabase("philter");
        }
    }
    @AfterEach void cleanup() { if (isolated != null) isolated.drop(); }
    private LedgerDataService service(MongoClient client) {
        var audit = mock(AuditEventPublisher.class); var signing = mock(SigningService.class);
        try { when(signing.signLedgerEntry(anyString())).thenReturn(new SigningService.LedgerSignature("test-signature", "test-key")); }
        catch (Exception ex) { throw new AssertionError(ex); }
        when(signing.verifyLedgerEntry(anyString(), anyString(), anyString())).thenReturn(true);
        return new LedgerDataService(client, encryption, audit, new LegalHoldDataService(client, audit), signing);
    }
    private void genesis(LedgerDataService ledger, String doc) throws Exception {
        ledger.initializeLedger(owner, doc, "input-hash", "file.pdf", "policy", 0, "policy-hash");
    }
    private LedgerEntity next(LedgerDataService ledger, String doc) throws Exception {
        return new LedgerEntity(owner, doc, "123-45-6789", "redacted", 0, "input-hash",
                ledger.getLatestTransaction(owner, doc).getHash(), "file.pdf", "ssn", "policy", 0, "policy-hash");
    }
    private ServiceResponse delete(LedgerDataService ledger, int path) {
        return path == 0 ? ledger.deleteByDocumentId("audit", owner, "doc", "test")
                : ledger.deleteAllByUserId("audit", owner);
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void openChainCannotBeDeletedBetweenAppends(int path) throws Exception {
        var writer = service(mongoClient); genesis(writer, "doc"); var entry = next(writer, "doc");
        assertEquals(409, delete(service(mongoClient), path).getStatusCode());
        writer.addTransaction(entry); writer.completeChain(owner, "doc");
        assertTrue(writer.validateChain(owner, "doc").valid());
        assertEquals(2, writer.getChain(owner, "doc").size());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void deletionCannotOvertakeAnInFlightInsert(int path) throws Exception {
        var ledger = service(mongoClient); genesis(ledger, "doc"); var entry = next(ledger, "doc");
        var client = mock(com.mongodb.client.MongoClient.class, org.mockito.AdditionalAnswers.delegatesTo(mongoClient)); var database = spy(mongoClient.getDatabase("philter"));
        doReturn(database).when(client).getDatabase("philter");
        var entries = spy(database.getCollection("ledger")); doReturn(entries).when(database).getCollection("ledger");
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(inv -> { entered.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS)); return inv.callRealMethod(); })
                .when(entries).insertOne(any(Document.class));
        var writer = service(client);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var writing = executor.submit(() -> writer.addTransaction(entry));
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertEquals(409, delete(ledger, path).getStatusCode());
                assertEquals(1, ledger.getChain(owner, "doc").size());
            } finally { resume.countDown(); }
            writing.get(10, TimeUnit.SECONDS);
        }
        writer.completeChain(owner, "doc"); assertTrue(ledger.validateChain(owner, "doc").valid());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void completedDeletionPermanentlyFencesLateAppendsAndNewGenesis(int path) throws Exception {
        var ledger = service(mongoClient); genesis(ledger, "doc"); var late = next(ledger, "doc");
        ledger.completeChain(owner, "doc"); assertTrue(delete(service(mongoClient), path).isSuccessful());
        assertThrows(IllegalStateException.class, () -> ledger.addTransaction(late));
        assertThrows(IllegalStateException.class, () -> genesis(ledger, "doc"));
        assertThrows(IllegalStateException.class, () -> ledger.completeChain(owner, "doc"));
        assertTrue(ledger.getChain(owner, "doc").isEmpty());
        assertTrue(delete(service(mongoClient), path).isSuccessful());
    }
    @Test void bulkDeletionDoesNotSweepUpANewChain() throws Exception {
        var ledger = service(mongoClient); genesis(ledger, "doc"); ledger.completeChain(owner, "doc");
        var client = mock(com.mongodb.client.MongoClient.class, org.mockito.AdditionalAnswers.delegatesTo(mongoClient)); var database = spy(mongoClient.getDatabase("philter"));
        doReturn(database).when(client).getDatabase("philter");
        var entries = spy(database.getCollection("ledger")); doReturn(entries).when(database).getCollection("ledger");
        doReturn(entries).when(entries).withReadPreference(any()); doReturn(entries).when(entries).withWriteConcern(any());
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        doAnswer(inv -> { entered.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS)); return inv.callRealMethod(); })
                .when(entries).deleteMany(any(Bson.class));
        var deleting = service(client);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var deletion = executor.submit(() -> delete(deleting, 1));
            try { assertTrue(entered.await(10, TimeUnit.SECONDS)); genesis(ledger, "new-doc"); }
            finally { resume.countDown(); }
            assertTrue(deletion.get(10, TimeUnit.SECONDS).isSuccessful());
        }
        ledger.completeChain(owner, "new-doc");
        assertTrue(ledger.getChain(owner, "doc").isEmpty());
        assertTrue(ledger.validateChain(owner, "new-doc").valid());
    }
}
