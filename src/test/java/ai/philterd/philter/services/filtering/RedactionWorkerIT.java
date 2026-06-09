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
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        pendingDocumentDataService = new PendingDocumentDataService(mongoClient, mock(AuditEventPublisher.class));
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
        entity.setSubmittedAt(new Date());
        return entity;
    }

    /** Stubs the redaction pipeline to return the given redacted bytes for any filter call. */
    private void stubRedactionReturns(final byte[] output) throws Exception {
        final BinaryDocumentFilterResult result = mock(BinaryDocumentFilterResult.class);
        when(result.getDocument()).thenReturn(output);
        when(redactionService.filter(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RedactionOutcome(result, new AppliedPolicy("default", 0, "hash")));
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

        // The redaction ran with the job's policy/user/context/input.
        final ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(redactionService).filter(eq("default"), eq(user), eq(""), body.capture(), eq(MimeType.APPLICATION_PDF), any());
        assertArrayEquals(new byte[]{1, 2, 3}, body.getValue());
    }

    @Test
    void pollMarksJobFailedWhenRedactionThrows() throws Exception {
        final ObjectId user = new ObjectId();
        pendingDocumentDataService.save(newPending(user, "doc-1"));

        when(redactionService.filter(any(), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        worker.poll();

        final PendingDocumentEntity failed = pendingDocumentDataService.findOneByDocumentIdAndUserId("doc-1", user);
        assertEquals(PendingDocumentEntity.STATUS_FAILED, failed.getStatus());
        assertEquals("boom", failed.getErrorMessage());
        assertNull(failed.getInput(), "input must be cleared once failed");
    }

    @Test
    void pollDoesNothingWhenQueueIsEmpty() throws Exception {
        worker.poll();
        verify(redactionService, never()).filter(any(), any(), any(), any(), any());
    }

    @Test
    void crashedJobLeftProcessingIsReclaimedAndReprocessedToCompletion() throws Exception {
        final ObjectId user = new ObjectId();

        // Simulate a job a crashed worker claimed and never finished: PROCESSING with an old claim time
        // (older than the worker's 10-minute stuck threshold).
        final PendingDocumentEntity stuck = newPending(user, "doc-1");
        stuck.setStatus(PendingDocumentEntity.STATUS_PROCESSING);
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

}
