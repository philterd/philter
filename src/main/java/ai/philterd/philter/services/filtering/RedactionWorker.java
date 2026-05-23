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

import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class RedactionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedactionWorker.class);

    private static final long STUCK_JOB_THRESHOLD_MS = 10L * 60L * 1000L;

    private final PendingDocumentDataService pendingDocumentDataService;
    private final RedactionService redactionService;
    private final UserService userService;
    private final WebhookDeliveryDataService webhookDeliveryDataService;
    private final Gson gson;
    private final String workerId = "philter-worker-" + UUID.randomUUID();

    public RedactionWorker(final PendingDocumentDataService pendingDocumentDataService,
                           final RedactionService redactionService,
                           final UserService userService,
                           final WebhookDeliveryDataService webhookDeliveryDataService,
                           final Gson gson) {
        this.pendingDocumentDataService = pendingDocumentDataService;
        this.redactionService = redactionService;
        this.userService = userService;
        this.webhookDeliveryDataService = webhookDeliveryDataService;
        this.gson = gson;
    }

    @Scheduled(fixedDelayString = "${philter.worker.poll-interval-ms:5000}", initialDelay = 5000)
    public void poll() {

        try {
            final Date stuckCutoff = new Date(System.currentTimeMillis() - STUCK_JOB_THRESHOLD_MS);
            final long reclaimed = pendingDocumentDataService.reclaimStuckJobs(stuckCutoff);
            if (reclaimed > 0) {
                LOGGER.warn("Reclaimed {} stuck job(s) older than {}", reclaimed, stuckCutoff);
            }

            final PendingDocumentEntity job = pendingDocumentDataService.claimNextPending(workerId);
            if (job == null) {
                return;
            }

            process(job);

        } catch (Exception ex) {
            LOGGER.error("Worker poll failed", ex);
        }

    }

    private void process(final PendingDocumentEntity job) {

        LOGGER.info("Processing pending document {} for user {}", job.getDocumentId(), job.getUserId());

        try {
            final MimeType inputMimeType = MimeType.valueOf(job.getInputMimeType());

            final AbstractFilterResult result = redactionService.filter(
                    job.getPolicyName(),
                    job.getUserId(),
                    job.getContextName(),
                    job.getInput(),
                    inputMimeType
            );

            final byte[] output;
            if (result instanceof BinaryDocumentFilterResult binaryResult) {
                output = binaryResult.getDocument();
            } else {
                throw new IllegalStateException("Async worker received non-binary filter result for document " + job.getDocumentId());
            }

            pendingDocumentDataService.markComplete(job.getId(), output);
            LOGGER.info("Completed pending document {}", job.getDocumentId());

            enqueueWebhook(job, WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE, null);

        } catch (Exception ex) {
            LOGGER.error("Redaction failed for document {}", job.getDocumentId(), ex);
            pendingDocumentDataService.markFailed(job.getId(), ex.getMessage());

            enqueueWebhook(job, WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_FAILED, ex.getMessage());
        }

    }

    private void enqueueWebhook(final PendingDocumentEntity job, final String eventType, final String errorMessage) {

        try {
            final UserEntity user = userService.findOneById(job.getUserId());
            if (user == null || user.getWebhookUrl() == null || user.getWebhookUrl().isBlank()) {
                return;
            }
            if (user.getWebhookSecret() == null || user.getWebhookSecret().isBlank()) {
                LOGGER.warn("Skipping webhook for user {}: URL configured but no secret set.", user.getId());
                return;
            }

            final Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", eventType);
            payload.put("documentId", job.getDocumentId());
            payload.put("fileName", job.getFileName());
            payload.put("status", job.getStatus());
            payload.put("timestamp", new Date().toInstant().toString());
            if (errorMessage != null) {
                payload.put("error", errorMessage);
            }

            final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
            delivery.setUserId(job.getUserId());
            delivery.setDocumentId(job.getDocumentId());
            delivery.setEventType(eventType);
            delivery.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
            delivery.setUrl(user.getWebhookUrl());
            delivery.setSecret(user.getWebhookSecret());
            delivery.setPayload(gson.toJson(payload));
            delivery.setAttempts(0);
            final Date now = new Date();
            delivery.setCreatedAt(now);
            delivery.setUpdatedAt(now);
            delivery.setNextAttemptAt(now);

            webhookDeliveryDataService.save(delivery);

        } catch (Exception ex) {
            LOGGER.error("Failed to enqueue webhook for document {}", job.getDocumentId(), ex);
        }

    }

}
