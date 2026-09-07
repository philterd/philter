package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.Date;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@EnabledIfSystemProperty(named = "philter.test.mongoUri", matches = ".+")
class RemainingP2MongoIT extends RemainingP2IT {
    @Test void realTtlCollectsBothDeliveryOutcomesAndPreservesUndispatchedJobs() throws Exception {
        new PendingDocumentDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
        new WebhookDeliveryDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
        var db = mongoClient.getDatabase("philter"); var deliveries = db.getCollection("webhook_deliveries");
        Date expired = new Date(0);
        deliveries.insertMany(List.of(new Document("status", "DELIVERED").append("completed_at", expired),
                new Document("status", "FAILED").append("completed_at", expired), new Document("status", "PENDING")));
        var jobs = db.getCollection("pending_documents");
        ObjectId waiting = new ObjectId();
        jobs.insertMany(List.of(new Document("_id", waiting).append("status", "COMPLETE").append("notification_pending", true)
                        .append("completed_at", expired),
                new Document("status", "COMPLETE").append("notification_pending", false).append("retention_at", expired)));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while ((deliveries.countDocuments() != 1 || jobs.countDocuments() != 1) && System.nanoTime() < deadline) Thread.sleep(100);
        assertEquals(1, deliveries.countDocuments());
        assertEquals("PENDING", deliveries.find().first().getString("status"));
        assertEquals(1, jobs.countDocuments()); assertNotNull(jobs.find(new Document("_id", waiting)).first());
    }
}
