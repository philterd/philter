package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.QueueCapacityException;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.*;
import ai.philterd.philter.services.filtering.RedactionService;
import ai.philterd.philter.services.filtering.RedactionWorker;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import ai.philterd.philter.utils.EnvUtils;
import com.google.gson.Gson;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.junit.jupiter.api.*;
import java.util.Date;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemainingP2IT extends AbstractMongoIT {
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private com.mongodb.client.MongoDatabase isolated;
    @BeforeEach void useRealServerWhenRequested() {
        String uri = System.getProperty("philter.test.mongoUri");
        if (uri != null) {
            mongoClient.close(); mongoClient = spy(MongoClients.create(uri));
            isolated = mongoClient.getDatabase("philter_p2_" + MongoIds.newId());
            doReturn(isolated).when(mongoClient).getDatabase("philter");
        }
    }
    @AfterEach void cleanup() { if (isolated != null) isolated.drop(); }
    private PendingDocumentDataService jobs() { return new MongoPendingDocumentDataService(mongoClient, new TestEncryptionService(), audit); }
    private PendingDocumentEntity job(String owner) {
        var job = new PendingDocumentEntity(); job.setUserId(owner); job.setDocumentId(MongoIds.newId());
        job.setStatus("PENDING"); job.setInput(new byte[10]); job.setSubmittedAt(new Date());
        return job;
    }
    private PendingDocumentEntity complete(PendingDocumentDataService jobs, String owner) {
        jobs.save(job(owner)); var claim = jobs.claimNextPending("worker");
        assertTrue(jobs.beginPublication(claim.getId(), claim.getClaimToken()));
        assertTrue(jobs.markComplete(claim.getId(), owner, claim.getClaimToken(), new byte[]{1}));
        return jobs.findOneByDocumentIdAndUserId(claim.getDocumentId(), owner);
    }
    private RedactionWorker reconciler(PendingDocumentDataService jobs, WebhookDeliveryDataService deliveries) {
        var users = mock(UserService.class); var user = new UserEntity();
        user.setWebhookUrl("https://example.com/hook"); user.setWebhookSecret("private-signing-secret");
        when(users.findOneById(any())).thenReturn(user);
        return new RedactionWorker(jobs, mock(RedactionService.class), users, deliveries,
                mock(PolicyVersionDataService.class), new Gson());
    }
    @Test void completionSurvivesRestartAndFailedEnqueueWithoutLosingIntent() {
        var jobs = jobs(); var owner = MongoIds.newId(); var terminal = complete(jobs, owner);
        var raw = mongoClient.getDatabase("philter").getCollection("pending_documents");
        assertTrue(raw.find(new Document("_id", MongoIds.objectId(terminal.getId()))).first().getBoolean("notification_pending"));
        assertNull(raw.find(new Document("_id", MongoIds.objectId(terminal.getId()))).first().get("retention_at"));
        assertEquals(409, assertThrows(QueueCapacityException.class,
                () -> jobs.deleteByDocumentIdAndUserId(terminal.getDocumentId(), owner)).getStatus());
        var deliveries = spy(new MongoWebhookDeliveryDataService(mongoClient, new TestEncryptionService(), audit));
        doThrow(new IllegalStateException("enqueue unavailable")).doCallRealMethod().when(deliveries).enqueueOnce(any());
        var restarted = reconciler(jobs(), deliveries);
        restarted.reconcileNotifications();
        assertEquals(1, jobs.pendingNotifications(10).size());
        restarted.reconcileNotifications();
        assertEquals(0, jobs.pendingNotifications(10).size());
        assertNotNull(raw.find(new Document("_id", MongoIds.objectId(terminal.getId()))).first().getDate("retention_at"));
        var delivery = mongoClient.getDatabase("philter").getCollection("webhook_deliveries").find().first();
        assertEquals(terminal.getId(), MongoIds.hex(delivery.getObjectId("_id")));
        assertFalse(delivery.toJson().contains("private-signing-secret"));
    }
    @Test void enqueueThenInterruptedAcknowledgementIsIdempotent() {
        var jobs = spy(jobs()); complete(jobs, MongoIds.newId());
        var deliveries = new MongoWebhookDeliveryDataService(mongoClient, new TestEncryptionService(), audit);
        doThrow(new IllegalStateException("interrupted after insert")).doCallRealMethod().when(jobs).acknowledgeNotification(any());
        var worker = reconciler(jobs, deliveries);
        worker.reconcileNotifications(); worker.reconcileNotifications();
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("webhook_deliveries").countDocuments());
        assertTrue(jobs.pendingNotifications(10).isEmpty());
    }
    @Test void exhaustedReclaimsAlsoProduceNotifications() {
        var jobs = jobs(); var pending = job(MongoIds.newId()); jobs.save(pending);
        var claim = jobs.claimNextPending("lost");
        mongoClient.getDatabase("philter").getCollection("pending_documents").updateOne(new Document("_id", MongoIds.objectId(claim.getId())),
                new Document("$set", new Document("claim_expires_at", new Date(0)).append("reclaim_count", 3)));
        jobs.reclaimStuckJobs(new Date(), 3);
        assertEquals("FAILED", jobs.pendingNotifications(10).getFirst().getStatus());
        reconciler(jobs, new MongoWebhookDeliveryDataService(mongoClient, new TestEncryptionService(), audit)).reconcileNotifications();
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("webhook_deliveries").countDocuments());
    }
    @Test void uncertainAdmissionRetainsGuardUntilOperatorRecovery() {
        assertThrows(IllegalStateException.class, () -> new QueueAdmission(mongoClient).execute(() -> { throw new IllegalStateException("uncertain insert"); }));
        assertEquals(503, assertThrows(QueueCapacityException.class, () -> jobs().save(job(MongoIds.newId()))).getStatus());
    }
    @Test void resolvedConfigurationIsNotRetainedAfterJobDeletion() {
        var jobs = jobs(); var pending = job(MongoIds.newId());
        pending.setEffectiveJson("{\"key\":\"sensitive-configuration\"}");
        pending.setEffectiveHash(PolicyVersionDataService.contentHash(pending.getEffectiveJson()));
        jobs.save(pending);
        var stored = jobs.findOneByDocumentIdAndUserId(pending.getDocumentId(), pending.getUserId());
        assertEquals(pending.getEffectiveJson(), stored.getEffectiveJson());
        var database = mongoClient.getDatabase("philter");
        var ciphertext = database.getCollection("pending_documents").find().first().getString("effective_json");
        jobs.deleteByDocumentIdAndUserId(pending.getDocumentId(), pending.getUserId());
        // Only the job held the configuration, so deleting the job leaves no copy of it.
        for (var name : database.listCollectionNames()) {
            for (var document : database.getCollection(name).find()) {
                assertFalse(document.toJson().contains(ciphertext), name + " still holds the configuration");
            }
        }
    }
    @Test void anotherInstanceWaitsForAnActiveAdmissionToFinish() throws Exception {
        var guard = new QueueAdmission(mongoClient); var contender = jobs();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holding = executor.submit(() -> guard.execute(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return null;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var waiting = executor.submit(() -> contender.save(job(MongoIds.newId())));
                // Keep the guard occupied long enough for the contender's first acquisition attempt.
                Thread.sleep(100);
                release.countDown();
                assertNotNull(waiting.get(5, TimeUnit.SECONDS));
                holding.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        assertEquals(1, contender.queueStats(null).jobs());
    }

}
