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
import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedactionWorkerTest {

    @Mock private PendingDocumentDataService pendingDocumentDataService;
    @Mock private RedactionService redactionService;
    @Mock private UserService userService;
    @Mock private WebhookDeliveryDataService webhookDeliveryDataService;
    @Mock private PolicyVersionDataService policyVersionDataService;

    private RedactionWorker worker;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(pendingDocumentDataService.beginPublication(any(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(pendingDocumentDataService.markComplete(any(), any(), any(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(pendingDocumentDataService.markFailed(any(), any(), any())).thenReturn(true);
        worker = new RedactionWorker(pendingDocumentDataService, redactionService,
                userService, webhookDeliveryDataService, policyVersionDataService, new Gson());
    }

    private static RedactionOutcome outcome(final ai.philterd.phileas.model.filtering.AbstractFilterResult result) {
        return new RedactionOutcome("doc-worker", result, new AppliedPolicy("default", 0, "hash"));
    }

    private PendingDocumentEntity pdfJob() {
        final PendingDocumentEntity job = new PendingDocumentEntity();
        job.setId(new ObjectId());
        job.setClaimToken("attempt");
        job.setUserId(new ObjectId());
        job.setDocumentId("doc-1");
        job.setInputMimeType(MimeType.APPLICATION_PDF.name());
        job.setPolicyName("default");
        job.setContextName("none");
        job.setInput("%PDF-1.7".getBytes());
        return job;
    }

    private static BinaryDocumentFilterResult binaryResult(final byte[] document) {
        return new BinaryDocumentFilterResult(document, "none",
                new Explanation(Collections.emptyList(), Collections.emptyList()), 0L, Collections.emptyList());
    }

    @Test
    void noPendingJobDoesNothing() throws Exception {
        when(pendingDocumentDataService.reclaimStuckJobs(any(), anyInt())).thenReturn(0L);
        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(null);

        worker.poll();

        verify(redactionService, never()).filter(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(pendingDocumentDataService, never()).markComplete(any(), any(), any(), any());
        verify(pendingDocumentDataService, never()).markFailed(any(), any(), any());
    }

    @Test
    void successfulJobIsCompletedWithRedactedBytes() throws Exception {
        final PendingDocumentEntity job = pdfJob();
        final byte[] redacted = "redacted-pdf".getBytes();

        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(job, (PendingDocumentEntity) null);
        when(redactionService.filter(eq("default"), eq(job.getUserId()), eq("none"), any(byte[].class), eq(MimeType.APPLICATION_PDF), any(), any(), any(), any()))
                .thenAnswer(invocation -> { invocation.getArgument(8, Runnable.class).run(); return outcome(binaryResult(redacted)); });

        worker.poll();

        final ArgumentCaptor<byte[]> outputCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(pendingDocumentDataService).markComplete(eq(job.getId()), any(), eq(job.getClaimToken()), outputCaptor.capture());
        assertEquals("redacted-pdf", new String(outputCaptor.getValue()));
        verify(pendingDocumentDataService, never()).markFailed(any(), any(), any());
    }

    @Test
    void redactionFailureMarksJobFailed() throws Exception {
        final PendingDocumentEntity job = pdfJob();

        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(job, (PendingDocumentEntity) null);
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        worker.poll();

        verify(pendingDocumentDataService).markFailed(eq(job.getId()), eq(job.getClaimToken()), eq("boom"));
        verify(pendingDocumentDataService, never()).markComplete(any(), any(), any(), any());
    }

    @Test
    void nonBinaryResultMarksJobFailed() throws Exception {
        final PendingDocumentEntity job = pdfJob();

        // A text result for a binary job is a programming error; the worker must fail the job, not crash the poller.
        final TextFilterResult textResult = new TextFilterResult("redacted", "none", 0,
                new Explanation(Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), 0L);

        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(job, (PendingDocumentEntity) null);
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> { invocation.getArgument(8, Runnable.class).run(); return outcome(textResult); });

        worker.poll();

        verify(pendingDocumentDataService).markFailed(eq(job.getId()), eq(job.getClaimToken()), any());
        verify(pendingDocumentDataService, never()).markComplete(any(), any(), any(), any());
    }

    @Test
    void completionEnqueuesWebhookWhenConfigured() throws Exception {
        final PendingDocumentEntity job = pdfJob();

        final UserEntity user = new UserEntity();
        user.setId(job.getUserId());
        user.setWebhookUrl("https://example.com/hook");
        user.setWebhookSecret("a-secret-value");

        when(userService.findOneById(job.getUserId())).thenReturn(user);

        job.setStatus(PendingDocumentEntity.STATUS_COMPLETE);
        job.setCompletedAt(new java.util.Date());
        when(pendingDocumentDataService.pendingNotifications(100)).thenReturn(java.util.List.of(job));
        worker.reconcileNotifications();

        final ArgumentCaptor<WebhookDeliveryEntity> captor = ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(webhookDeliveryDataService).enqueueOnce(captor.capture());
        final WebhookDeliveryEntity delivery = captor.getValue();
        assertEquals(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE, delivery.getEventType());
        assertEquals("https://example.com/hook", delivery.getUrl());
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, delivery.getStatus());
    }

    @Test
    void webhookSkippedWhenUrlConfiguredButNoSecret() throws Exception {
        final PendingDocumentEntity job = pdfJob();

        final UserEntity user = new UserEntity();
        user.setId(job.getUserId());
        user.setWebhookUrl("https://example.com/hook");
        // no secret

        when(userService.findOneById(job.getUserId())).thenReturn(user);

        job.setStatus(PendingDocumentEntity.STATUS_COMPLETE);
        job.setCompletedAt(new java.util.Date());
        when(pendingDocumentDataService.pendingNotifications(100)).thenReturn(java.util.List.of(job));
        worker.reconcileNotifications();

        verify(webhookDeliveryDataService, never()).enqueueOnce(any());
    }

    @Test
    void stuckJobsAreReclaimedEachPoll() {
        when(pendingDocumentDataService.reclaimStuckJobs(any(), anyInt())).thenReturn(2L);
        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(null);

        worker.poll();

        verify(pendingDocumentDataService).reclaimStuckJobs(any(), anyInt());
    }

    @Test
    void claimFailureDoesNotPropagate() {
        // A failure while claiming must be swallowed so the scheduled poller keeps running.
        when(pendingDocumentDataService.reclaimStuckJobs(any(), anyInt())).thenReturn(0L);
        when(pendingDocumentDataService.claimNextPending(any())).thenThrow(new RuntimeException("mongo down"));

        worker.poll(); // must not throw
    }

    @Test
    void missingCustomListMarksJobFailedWithoutPublishingOutput() throws Exception {
        final PendingDocumentEntity job = pdfJob();
        final String message = "Policy references unavailable custom lists: required-names.";
        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(job, (PendingDocumentEntity) null);
        when(redactionService.filter(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new ai.philterd.philter.services.policies.PolicyResolutionException(message));

        worker.poll();

        verify(pendingDocumentDataService).markFailed(job.getId(), job.getClaimToken(), message);
        verify(pendingDocumentDataService, never()).markComplete(any(), any(), any(), any());
    }

    @Test
    void missingExplicitPinFailsWithoutLivePolicyFallback() throws Exception {
        final PendingDocumentEntity job = pdfJob();
        job.setPolicyContentHash("missing-snapshot");
        when(pendingDocumentDataService.claimNextPending(any())).thenReturn(job, (PendingDocumentEntity) null);
        worker.poll();
        verify(pendingDocumentDataService).markFailed(job.getId(), job.getClaimToken(),
                "Pinned policy snapshot is missing: missing-snapshot");
        org.mockito.Mockito.verifyNoInteractions(redactionService);
        verify(pendingDocumentDataService, never()).markComplete(any(), any(), any(), any());
    }
}
