package ai.philterd.philter.data.services;

import ai.philterd.philter.api.exceptions.PayloadTooLargeException;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.services.policies.EffectiveConfigurationLimits;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import java.util.Date;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class R1AdmissionIT extends AbstractMongoIT {
    private com.mongodb.client.MongoDatabase isolated;
    @org.junit.jupiter.api.BeforeEach void realServerWhenRequested() {
        String uri = System.getProperty("philter.test.mongoUri");
        if (uri != null) {
            mongoClient.close(); mongoClient = spy(com.mongodb.client.MongoClients.create(uri));
            isolated = mongoClient.getDatabase("philter_r1_" + new ObjectId());
            doReturn(isolated).when(mongoClient).getDatabase("philter");
        }
    }
    @org.junit.jupiter.api.AfterEach void cleanup() { if (isolated != null) isolated.drop(); }
    private final TestEncryptionService encryption = new TestEncryptionService();
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    private PendingDocumentDataService service() {
        return new PendingDocumentDataService(mongoClient, encryption, audit);
    }
    private PendingDocumentEntity job() {
        var job = new PendingDocumentEntity();
        job.setUserId(new ObjectId()); job.setDocumentId(new ObjectId().toHexString());
        job.setStatus("PENDING"); job.setInput(new byte[10]); job.setSubmittedAt(new Date());
        return job;
    }
    private void assertOtherOwnerCanSubmit() {
        var secondInstance = service();
        assertNotNull(secondInstance.save(job()));
        assertEquals(1, secondInstance.queueStats(null).jobs());
        var guard = mongoClient.getDatabase("philter").getCollection("queue_admission").find().first();
        assertFalse(guard.containsKey("token"));
    }

    @Test void oversizedEffectiveConfigurationDoesNotAcquireAdmission() {
        var jobs = service(); var job = job();
        job.setEffectiveJson("x".repeat(EffectiveConfigurationLimits.MAX_BYTES + 1));
        job.setEffectiveHash(PolicyVersionDataService.contentHash(job.getEffectiveJson()));
        assertThrows(PayloadTooLargeException.class, () -> jobs.save(job));
        assertEquals(0, mongoClient.getDatabase("philter").getCollection("execution_snapshots").countDocuments());
        assertOtherOwnerCanSubmit();
    }

    @Test void invalidFingerprintDoesNotAcquireAdmission() {
        var jobs = service(); var job = job(); job.setEffectiveJson("{}"); job.setEffectiveHash("wrong");
        assertThrows(IllegalArgumentException.class, () -> jobs.save(job));
        assertOtherOwnerCanSubmit();
    }

    @Test void completeEncryptedRecordIsCheckedBeforeSnapshotOrAdmission() {
        var jobs = service(); var job = job();
        job.setInput(new byte[PendingDocumentDataService.MAX_ADMISSION_BSON_BYTES]);
        job.setEffectiveJson("{}"); job.setEffectiveHash(PolicyVersionDataService.contentHash("{}"));
        assertThrows(PayloadTooLargeException.class, () -> jobs.save(job));
        assertEquals(0, mongoClient.getDatabase("philter").getCollection("execution_snapshots").countDocuments());
        assertOtherOwnerCanSubmit();
    }

    @Test void bsonPreflightAcceptsBoundaryAndRejectsOneByteOver() {
        // BSON document with one binary field named "payload" has 19 bytes of overhead.
        assertDoesNotThrow(() -> PendingDocumentDataService.requireStorable(new Document("payload",
                new Binary(new byte[PendingDocumentDataService.MAX_ADMISSION_BSON_BYTES - 19]))));
        assertThrows(PayloadTooLargeException.class, () -> PendingDocumentDataService.requireStorable(
                new Document("payload", new Binary(new byte[PendingDocumentDataService.MAX_ADMISSION_BSON_BYTES - 18]))));
    }

    @Test void snapshotStorageFailureCannotStrandAdmission() {
        var client = mock(com.mongodb.client.MongoClient.class, org.mockito.AdditionalAnswers.delegatesTo(mongoClient)); var database = spy(mongoClient.getDatabase("philter"));
        doReturn(database).when(client).getDatabase("philter");
        var snapshots = spy(database.getCollection("execution_snapshots"));
        doReturn(snapshots).when(database).getCollection("execution_snapshots");
        doReturn(snapshots).when(snapshots).withWriteConcern(any(WriteConcern.class));
        doThrow(new IllegalStateException("uncertain snapshot write")).when(snapshots)
                .updateOne(any(Bson.class), any(Bson.class), any(UpdateOptions.class));
        var jobs = new PendingDocumentDataService(client, encryption, audit); var job = job();
        job.setEffectiveJson("{}"); job.setEffectiveHash(PolicyVersionDataService.contentHash("{}"));
        assertThrows(IllegalStateException.class, () -> jobs.save(job));
        assertOtherOwnerCanSubmit();
    }
}
