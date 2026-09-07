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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link RedactionWorker} against a real (in-memory) MongoDB and a mocked
 * redaction pipeline. These exercise the worker's orchestration end to end: claiming a pending job,
 * marking it complete with the redacted bytes, marking it failed when redaction throws, doing nothing
 * when the queue is empty, and recovering a job left {@code PROCESSING} by a crashed worker.
 */
class RedactionWorkerIT extends AbstractMongoIT {

    private PendingDocumentDataService pendingDocumentDataService;
    private RedactionService redactionService;
    private UserService userService;
    private WebhookDeliveryDataService webhookDeliveryDataService;
    private RedactionWorker worker;

    @BeforeEach
    void setUp() {
        pendingDocumentDataService = new PendingDocumentDataService(mongoClient, new ai.philterd.philter.testutil.TestEncryptionService(), mock(AuditEventPublisher.class));
        redactionService = mock(RedactionService.class);
        userService = mock(UserService.class); // returns null user -> no webhook enqueued
        webhookDeliveryDataService = mock(WebhookDeliveryDataService.class);
        worker = new RedactionWorker(pendingDocumentDataService, redactionService, userService,
                webhookDeliveryDataService, mock(PolicyVersionDataService.class), new Gson());
    }

    private PendingDocumentEntity newPending(final ObjectId userId, final String documentId) {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setPolicyName("default");
        entity.setContextName("");
        entity.setInputMimeType(MimeType.APPLICATION_PDF.name());
        entity.setOutputMimeType(MimeType.APPLICATION_PDF.name());
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        entity.setInput(new byte[]{1, 2, 3});
        entity.setFileName("invoice-42.pdf");
        entity.setSubmittedAt(new Date());
        return entity;
    }

    /** Stubs the redaction pipeline to return the given redacted bytes for any filter call. */
    private void stubRedactionReturns(final byte[] output) throws Exception {
        final BinaryDocumentFilterResult result = mock(BinaryDocumentFilterResult.class);
        when(result.getDocument()).thenReturn(output);
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> { invocation.getArgument(8, Runnable.class).run(); return new RedactionOutcome("doc-worker", result, new AppliedPolicy("default", 0, "hash")); });
    }

    @Test
    void pollClaimsPendingJobRedactsItAndMarksComplete() throws Exception {
        final ObjectId user = new ObjectId();
        pendingDocumentDataService.save(newPending(user, "doc-1"));

        final byte[] redacted = new byte[]{9, 8, 7};
        stubRedactionReturns(redacted);

        worker.poll();

        final PendingDocumentEntity completed = pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-1", user);
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, completed.getStatus());
        assertArrayEquals(redacted, completed.getOutput());
        assertNull(completed.getInput(), "input must be cleared once complete");

        // The job's policy, input, filename and document id all reach the service.
        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(redactionService).filter(eq("default"), eq(user), eq(""), body.capture(),
                eq(MimeType.APPLICATION_PDF), any(), eq("invoice-42.pdf"), eq("doc-1"), any());
        assertArrayEquals(new byte[]{1, 2, 3}, body.getValue());
    }

    @Test
    void pollMarksJobFailedWhenRedactionThrows() throws Exception {
        final ObjectId user = new ObjectId();
        pendingDocumentDataService.save(newPending(user, "doc-1"));

        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        worker.poll();

        final PendingDocumentEntity failed = pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-1", user);
        assertEquals(PendingDocumentEntity.STATUS_FAILED, failed.getStatus());
        assertEquals("boom", failed.getErrorMessage());
        assertNull(failed.getInput(), "input must be cleared once failed");
    }

    @Test
    void pollDoesNothingWhenQueueIsEmpty() throws Exception {
        worker.poll();
        verify(redactionService, never()).filter(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void crashedJobLeftProcessingIsReclaimedAndReprocessedToCompletion() throws Exception {
        final ObjectId user = new ObjectId();

        // Simulate a job a crashed worker claimed and never finished: PROCESSING with an old claim time
        // (older than the worker's 10-minute stuck threshold).
        final PendingDocumentEntity stuck = newPending(user, "doc-1");
        stuck.setStatus(PendingDocumentEntity.STATUS_PROCESSING);
        stuck.setClaimToken("dead-attempt");
        stuck.setClaimExpiresAt(new Date(0));
        stuck.setClaimedBy("dead-worker");
        stuck.setClaimedAt(new Date(System.currentTimeMillis() - (11L * 60L * 1000L)));
        stuck.setStartedAt(stuck.getClaimedAt());
        pendingDocumentDataService.save(stuck);

        stubRedactionReturns(new byte[]{5});

        // A single poll reclaims the stuck job (back to PENDING), then claims and processes it.
        worker.poll();

        final PendingDocumentEntity recovered = pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-1", user);
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, recovered.getStatus());
        assertArrayEquals(new byte[]{5}, recovered.getOutput());
    }


    /** A user with a webhook configured, so the worker enqueues a delivery. */
    private void userWithWebhook(final ObjectId userId) {
        final ai.philterd.philter.data.entities.UserEntity user =
                mock(ai.philterd.philter.data.entities.UserEntity.class);
        when(user.getId()).thenReturn(userId);
        when(user.getWebhookUrl()).thenReturn("https://example.com/hook");
        when(user.getWebhookSecret()).thenReturn("the-shared-secret-1234567890");
        when(userService.findOneById(userId)).thenReturn(user);
    }

    private String enqueuedPayload() {
        final ArgumentCaptor<WebhookDeliveryEntity> delivery =
                ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(webhookDeliveryDataService).enqueueOnce(delivery.capture());
        return delivery.getValue().getPayload();
    }

    @Test
    @DisplayName("The completion webhook reports COMPLETE, not the status the job was claimed with")
    void completionWebhookReportsComplete() throws Exception {
        final ObjectId user = new ObjectId();
        userWithWebhook(user);
        pendingDocumentDataService.save(newPending(user, "doc-1"));
        stubRedactionReturns(new byte[]{9});

        worker.poll();

        final String payload = enqueuedPayload();
        assertTrue(payload.contains("\"status\":\"" + PendingDocumentEntity.STATUS_COMPLETE + "\""),
                "the completion event must not report PROCESSING: " + payload);
        assertTrue(payload.contains(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE));
    }

    @Test
    @DisplayName("The failure webhook reports FAILED")
    void failureWebhookReportsFailed() throws Exception {
        final ObjectId user = new ObjectId();
        userWithWebhook(user);
        pendingDocumentDataService.save(newPending(user, "doc-1"));
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        worker.poll();

        final String payload = enqueuedPayload();
        assertTrue(payload.contains("\"status\":\"" + PendingDocumentEntity.STATUS_FAILED + "\""),
                "the failure event must report FAILED: " + payload);
    }


    @Test
    @DisplayName("One poll drains the queue rather than taking a single job")
    void onePollDrainsTheQueue() throws Exception {
        final ObjectId user = new ObjectId();
        for (int i = 1; i <= 5; i++) {
            pendingDocumentDataService.save(newPending(user, "doc-" + i));
        }
        stubRedactionReturns(new byte[]{7});

        worker.poll();

        // Before, throughput was one document per poll interval however fast redaction ran.
        for (int i = 1; i <= 5; i++) {
            assertEquals(PendingDocumentEntity.STATUS_COMPLETE,
                    pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-" + i, user).getStatus(),
                    "doc-" + i + " must be processed by the same poll");
        }
    }

    @Test
    @DisplayName("A failing job does not stop the rest of the queue")
    void aFailingJobDoesNotStallTheQueue() throws Exception {
        final ObjectId user = new ObjectId();
        pendingDocumentDataService.save(newPending(user, "doc-bad"));
        pendingDocumentDataService.save(newPending(user, "doc-good"));

        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"))
                .thenAnswer(invocation -> { invocation.getArgument(8, Runnable.class).run(); return new RedactionOutcome("doc-good", binaryResultReturning(new byte[]{7}),
                        new AppliedPolicy("default", 0, "hash")); });

        worker.poll();

        assertEquals(PendingDocumentEntity.STATUS_FAILED,
                pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-bad", user).getStatus());
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE,
                pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-good", user).getStatus(),
                "a failure must not abandon the jobs behind it");
    }

    private static BinaryDocumentFilterResult binaryResultReturning(final byte[] output) {
        final BinaryDocumentFilterResult result = mock(BinaryDocumentFilterResult.class);
        when(result.getDocument()).thenReturn(output);
        return result;
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void pausedWorkerCannotPublishOrNotifyAfterNewWorkerCompletes(final boolean lateFailure) throws Exception {
        final ObjectId user = new ObjectId();
        userWithWebhook(user);
        pendingDocumentDataService.save(newPending(user, "race-doc"));
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var resume = new java.util.concurrent.CountDownLatch(1);
        final var publications = new java.util.concurrent.atomic.AtomicInteger();
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(resume.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    if (lateFailure) throw new IllegalStateException("old failure");
                    invocation.getArgument(8, Runnable.class).run();
                    publications.incrementAndGet();
                    return new RedactionOutcome("race-doc", binaryResultReturning(new byte[]{0}),
                            new AppliedPolicy("default", 0, "hash"));
                });
        try (final var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            final var oldWorker = executor.submit(worker::poll);
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                mongoClient.getDatabase("philter").getCollection("pending_documents").updateOne(
                        new org.bson.Document("document_id", "race-doc"),
                        new org.bson.Document("$set", new org.bson.Document("claim_expires_at", new Date(0))));
                final var newPipeline = mock(RedactionService.class);
                when(newPipeline.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                        .thenAnswer(invocation -> {
                            invocation.getArgument(8, Runnable.class).run();
                            publications.incrementAndGet();
                            return new RedactionOutcome("race-doc", binaryResultReturning(new byte[]{9}),
                                    new AppliedPolicy("default", 0, "hash"));
                        });
                final var newService = new PendingDocumentDataService(mongoClient,
                        new ai.philterd.philter.testutil.TestEncryptionService(), mock(AuditEventPublisher.class));
                new RedactionWorker(newService, newPipeline, userService, webhookDeliveryDataService,
                        mock(PolicyVersionDataService.class), new Gson()).poll();
            } finally {
                resume.countDown();
            }
            oldWorker.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        final var result = pendingDocumentDataService.findOneByDocumentIdAndUserId("race-doc", user);
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, result.getStatus());
        assertArrayEquals(new byte[]{9}, result.getOutput());
        assertEquals(1, publications.get());
        verify(webhookDeliveryDataService, org.mockito.Mockito.times(1)).enqueueOnce(any());
        assertTrue(enqueuedPayload().contains(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE));
    }

    @Test
    void heartbeatRunsWhileFilteringIsBlocked() throws Exception {
        final ObjectId user = new ObjectId();
        pendingDocumentDataService.save(newPending(user, "slow"));
        final var service = org.mockito.Mockito.spy(pendingDocumentDataService);
        final var renewed = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            final boolean accepted = (boolean) invocation.callRealMethod();
            if (accepted) renewed.countDown();
            return accepted;
        }).when(service).renewClaim(any(), any());
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    assertTrue(renewed.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    invocation.getArgument(8, Runnable.class).run();
                    return new RedactionOutcome("slow", binaryResultReturning(new byte[]{9}),
                            new AppliedPolicy("default", 0, "hash"));
                });
        new RedactionWorker(service, redactionService, userService, webhookDeliveryDataService,
                mock(PolicyVersionDataService.class), new Gson(), 10).poll();
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE,
                service.findOneByDocumentIdAndUserId("slow", user).getStatus());
    }
    @Test
    void queuedZipResultContainsTheRedactedPdf() throws Exception {
        final ObjectId owner = new ObjectId();
        final var job = newPending(owner, "zip-result");
        job.setOutputMimeType("application/zip");
        pendingDocumentDataService.save(job);
        byte[] pdf = "%PDF-1.7 redacted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        stubRedactionReturns(pdf);
        worker.poll();
        final var result = pendingDocumentDataService.findOneByDocumentIdAndUserId("zip-result", owner);
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, result.getStatus());
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(result.getOutput()))) {
            assertEquals("redacted.pdf", zip.getNextEntry().getName());
            assertArrayEquals(pdf, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
    }

}
