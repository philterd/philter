package ai.philterd.philter.data.services;

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
import org.bson.types.ObjectId;
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
            isolated = mongoClient.getDatabase("philter_p2_" + new ObjectId());
            doReturn(isolated).when(mongoClient).getDatabase("philter");
        }
    }
    @AfterEach void cleanup() { if (isolated != null) isolated.drop(); }
    private PendingDocumentDataService jobs() { return new PendingDocumentDataService(mongoClient, new TestEncryptionService(), audit); }
    private PendingDocumentEntity job(ObjectId owner) {
        var job = new PendingDocumentEntity(); job.setUserId(owner); job.setDocumentId(new ObjectId().toHexString());
        job.setStatus("PENDING"); job.setInput(new byte[10]); job.setSubmittedAt(new Date());
        return job;
    }
    private PendingDocumentEntity complete(PendingDocumentDataService jobs, ObjectId owner) {
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
        var jobs = jobs(); var owner = new ObjectId(); var terminal = complete(jobs, owner);
        var raw = mongoClient.getDatabase("philter").getCollection("pending_documents");
        assertTrue(raw.find(new Document("_id", terminal.getId())).first().getBoolean("notification_pending"));
        assertNull(raw.find(new Document("_id", terminal.getId())).first().get("retention_at"));
        assertEquals(409, assertThrows(QueueCapacityException.class,
                () -> jobs.deleteByDocumentIdAndUserId(terminal.getDocumentId(), owner)).getStatus());
        var deliveries = spy(new WebhookDeliveryDataService(mongoClient, new TestEncryptionService(), audit));
        doThrow(new IllegalStateException("enqueue unavailable")).doCallRealMethod().when(deliveries).enqueueOnce(any());
        var restarted = reconciler(jobs(), deliveries);
        restarted.reconcileNotifications();
        assertEquals(1, jobs.pendingNotifications(10).size());
        restarted.reconcileNotifications();
        assertEquals(0, jobs.pendingNotifications(10).size());
        assertNotNull(raw.find(new Document("_id", terminal.getId())).first().getDate("retention_at"));
        var delivery = mongoClient.getDatabase("philter").getCollection("webhook_deliveries").find().first();
        assertEquals(terminal.getId(), delivery.getObjectId("_id"));
        assertFalse(delivery.toJson().contains("private-signing-secret"));
    }
    @Test void enqueueThenInterruptedAcknowledgementIsIdempotent() {
        var jobs = spy(jobs()); complete(jobs, new ObjectId());
        var deliveries = new WebhookDeliveryDataService(mongoClient, new TestEncryptionService(), audit);
        doThrow(new IllegalStateException("interrupted after insert")).doCallRealMethod().when(jobs).acknowledgeNotification(any());
        var worker = reconciler(jobs, deliveries);
        worker.reconcileNotifications(); worker.reconcileNotifications();
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("webhook_deliveries").countDocuments());
        assertTrue(jobs.pendingNotifications(10).isEmpty());
    }
    @Test void exhaustedReclaimsAlsoProduceNotifications() {
        var jobs = jobs(); var pending = job(new ObjectId()); jobs.save(pending);
        var claim = jobs.claimNextPending("lost");
        mongoClient.getDatabase("philter").getCollection("pending_documents").updateOne(new Document("_id", claim.getId()),
                new Document("$set", new Document("claim_expires_at", new Date(0)).append("reclaim_count", 3)));
        jobs.reclaimStuckJobs(new Date(), 3);
        assertEquals("FAILED", jobs.pendingNotifications(10).getFirst().getStatus());
        reconciler(jobs, new WebhookDeliveryDataService(mongoClient, new TestEncryptionService(), audit)).reconcileNotifications();
        assertEquals(1, mongoClient.getDatabase("philter").getCollection("webhook_deliveries").countDocuments());
    }
    @Test void admissionEnforcesJobsAndBytesAndReleasesCapacityOnCompletion() {
        try (var env = mockStatic(EnvUtils.class, CALLS_REAL_METHODS)) {
            env.when(() -> EnvUtils.getInt("ASYNC_QUEUE_MAX_USER_JOBS", 32)).thenReturn(2);
            env.when(() -> EnvUtils.getLong("ASYNC_QUEUE_MAX_USER_BYTES", 256L*1024*1024)).thenReturn(20L);
            var jobs = jobs(); var owner = new ObjectId();
            jobs.save(job(owner)); jobs.save(job(owner));
            assertEquals(429, assertThrows(QueueCapacityException.class, () -> jobs.save(job(owner))).getStatus());
            var other = new ObjectId(); jobs.save(job(other));
            var first = jobs.claimNextPending("worker");
            assertEquals(owner, first.getUserId());
            var second = jobs.claimNextPending("worker");
            assertEquals(other, second.getUserId(), "Other owner must run before the first owner's second queued document");
            assertTrue(jobs.markFailed(first.getId(), first.getClaimToken(), "test"));
            jobs.save(job(owner));
            var oversized = job(new ObjectId()); oversized.setInput(new byte[21]);
            assertEquals(429, assertThrows(QueueCapacityException.class, () -> jobs.save(oversized)).getStatus());
            assertEquals(3, jobs.queueStats(null).jobs()); assertEquals(30, jobs.queueStats(null).bytes());
        }
    }
    @Test void parallelInstancesCannotOverAdmit() throws Exception {
        try (var env = mockStatic(EnvUtils.class, CALLS_REAL_METHODS)) {
            env.when(() -> EnvUtils.getInt("ASYNC_QUEUE_MAX_JOBS", 256)).thenReturn(2);
            var instances = java.util.stream.IntStream.range(0, 8).mapToObj(i -> jobs()).toList();
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = instances.stream().map(service -> executor.submit(() -> {
                    start.await();
                    try { service.save(job(new ObjectId())); } catch (QueueCapacityException full) { assertEquals(503, full.getStatus()); }
                    return null;
                })).toList();
                start.countDown(); for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            }
            assertTrue(instances.getFirst().queueStats(null).jobs() <= 2);
        }
    }
    @Test void uncertainAdmissionRetainsGuardUntilOperatorRecovery() {
        assertThrows(IllegalStateException.class, () -> new QueueAdmission(mongoClient).execute(() -> { throw new IllegalStateException("uncertain insert"); }));
        assertEquals(503, assertThrows(QueueCapacityException.class, () -> jobs().save(job(new ObjectId()))).getStatus());
    }
    @Test void executionSnapshotIsEncryptedAndRetainedAfterJobDeletion() {
        var jobs = jobs(); var pending = job(new ObjectId());
        pending.setEffectiveJson("{\"key\":\"sensitive-configuration\"}");
        pending.setEffectiveHash(PolicyVersionDataService.contentHash(pending.getEffectiveJson()));
        jobs.save(pending);
        var stored = jobs.findOneByDocumentIdAndUserId(pending.getDocumentId(), pending.getUserId());
        assertEquals(pending.getEffectiveJson(), stored.getEffectiveJson());
        jobs.deleteByDocumentIdAndUserId(pending.getDocumentId(), pending.getUserId());
        var snapshot = mongoClient.getDatabase("philter").getCollection("execution_snapshots").find().first();
        assertFalse(snapshot.toJson().contains("sensitive-configuration"));
        assertEquals(pending.getEffectiveJson(), new TestEncryptionService().decrypt(snapshot.getString("effective_json"), snapshot.getString("effective_key")));
    }
    @Test void saturatedOwnerDoesNotPreventAnotherOwnerFromBeingAdmitted() throws Exception {
        try (var env = mockStatic(EnvUtils.class, CALLS_REAL_METHODS)) {
            env.when(() -> EnvUtils.getInt("ASYNC_QUEUE_MAX_USER_JOBS", 32)).thenReturn(1);
            var fullOwner = new ObjectId(); var otherOwner = new ObjectId();
            var first = jobs(); var second = jobs(); first.save(job(fullOwner));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var full = executor.submit(() -> { start.await();
                    return assertThrows(QueueCapacityException.class, () -> first.save(job(fullOwner))).getStatus(); });
                var accepted = executor.submit(() -> { start.await(); return second.save(job(otherOwner)); });
                start.countDown();
                assertEquals(429, full.get(5, TimeUnit.SECONDS));
                assertNotNull(accepted.get(5, TimeUnit.SECONDS));
            }
            assertEquals(1, first.queueStats(fullOwner).jobs());
            assertEquals(1, first.queueStats(otherOwner).jobs());
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
                var waiting = executor.submit(() -> contender.save(job(new ObjectId())));
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
