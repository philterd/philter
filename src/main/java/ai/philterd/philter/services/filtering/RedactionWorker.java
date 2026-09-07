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
import ai.philterd.philter.data.services.PolicyVersionDataService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class RedactionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedactionWorker.class);


    private final PendingDocumentDataService pendingDocumentDataService;
    private final RedactionService redactionService;
    private final UserService userService;
    private final WebhookDeliveryDataService webhookDeliveryDataService;
    private final PolicyVersionDataService policyVersionDataService;
    private final Gson gson;
    private final long heartbeatIntervalMs;
    private final String workerId = "philter-worker-" + UUID.randomUUID();

    @org.springframework.beans.factory.annotation.Autowired
    public RedactionWorker(final PendingDocumentDataService pendingDocumentDataService,
                           final RedactionService redactionService,
                           final UserService userService,
                           final WebhookDeliveryDataService webhookDeliveryDataService,
                           final PolicyVersionDataService policyVersionDataService,
                           final Gson gson) {
        this(pendingDocumentDataService, redactionService, userService, webhookDeliveryDataService,
                policyVersionDataService, gson, 60_000);
    }

    RedactionWorker(final PendingDocumentDataService pendingDocumentDataService,
                    final RedactionService redactionService, final UserService userService,
                    final WebhookDeliveryDataService webhookDeliveryDataService,
                    final PolicyVersionDataService policyVersionDataService, final Gson gson,
                    final long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.pendingDocumentDataService = pendingDocumentDataService;
        this.redactionService = redactionService;
        this.userService = userService;
        this.webhookDeliveryDataService = webhookDeliveryDataService;
        this.policyVersionDataService = policyVersionDataService;
        this.gson = gson;
    }

    /** Attempts before a job that never completes is failed, so its input cannot be retained forever. */
    private static final int MAX_RECLAIMS = 3;

    @Scheduled(fixedDelayString = "${philter.worker.poll-interval-ms:5000}", initialDelay = 5000)
    public void poll() {

        try {
            reconcileNotifications();
            final Date stuckCutoff = new Date();
            final long reclaimed = pendingDocumentDataService.reclaimStuckJobs(stuckCutoff, MAX_RECLAIMS);
            if (reclaimed > 0) {
                LOGGER.warn("Reclaimed {} stuck job(s) older than {}", reclaimed, stuckCutoff);
            }

            // Drain, rather than one per poll: taking a single job capped throughput at one document
            // per poll interval however fast redaction actually ran.
            PendingDocumentEntity job;
            while ((job = pendingDocumentDataService.claimNextPending(workerId)) != null) {
                process(job);
            }

        } catch (Exception ex) {
            LOGGER.error("Worker poll failed", ex);
        }

    }

    private void process(final PendingDocumentEntity job) {

        LOGGER.info("Processing pending document {} for user {}", job.getDocumentId(), job.getUserId());

        final var heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
            final Thread thread = new Thread(runnable, "philter-job-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(() -> {
            try {
                pendingDocumentDataService.renewClaim(job.getId(), job.getClaimToken());
            } catch (Exception ex) {
                LOGGER.warn("Could not renew claim for document {}", job.getDocumentId(), ex);
            }
        }, heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS);

        try {
            final MimeType inputMimeType = MimeType.valueOf(job.getInputMimeType());

            // Redact with the policy version pinned when the request was accepted, so the deferred job
            // is governed by the version in force at request time. A missing explicit pin fails the job.
            PinnedPolicy pinnedPolicy = null;
            if (job.getPolicyContentHash() != null) {
                final var snapshot = policyVersionDataService.findByContentHash(job.getPolicyContentHash());
                if (snapshot != null) {
                    pinnedPolicy = new PinnedPolicy(job.getPolicyName(), job.getPolicyVersion(),
                            job.getPolicyContentHash(), snapshot.getPolicy(), job.getEffectiveJson(), job.getEffectiveHash());
                } else {
                    throw new IllegalStateException("Pinned policy snapshot is missing: " + job.getPolicyContentHash());
                }
            }

            final AbstractFilterResult result = redactionService.filter(
                    job.getPolicyName(),
                    job.getUserId(),
                    job.getContextName(),
                    job.getInput(),
                    inputMimeType,
                    pinnedPolicy,
                    job.getFileName(),
                    // The id already returned with the 202, so the job and its ledger chain match.
                    job.getDocumentId(),
                    () -> {
                        if (!pendingDocumentDataService.beginPublication(job.getId(), job.getClaimToken())) {
                            throw new IllegalStateException("Redaction job claim expired or was superseded.");
                        }
                    }
            ).result();

            final byte[] output;
            if (result instanceof BinaryDocumentFilterResult binaryResult) {
                output = BinaryOutput.encode(binaryResult.getDocument(), job.getOutputMimeType());
            } else {
                throw new IllegalStateException("Async worker received non-binary filter result for document " + job.getDocumentId());
            }

            if (!pendingDocumentDataService.markComplete(job.getId(), job.getUserId(), job.getClaimToken(), output)) {
                LOGGER.warn("Discarding stale completion for document {}", job.getDocumentId());
                return;
            }
            LOGGER.info("Completed pending document {}", job.getDocumentId());

            // markComplete writes the database, not this copy, which still reads PROCESSING.
            job.setStatus(PendingDocumentEntity.STATUS_COMPLETE);

            reconcileNotifications();

        } catch (Exception ex) {
            LOGGER.error("Redaction failed for document {}", job.getDocumentId(), ex);
            if (!pendingDocumentDataService.markFailed(job.getId(), job.getClaimToken(), ex.getMessage())) {
                LOGGER.warn("Discarding stale failure for document {}", job.getDocumentId());
                return;
            }

            job.setStatus(PendingDocumentEntity.STATUS_FAILED);

            reconcileNotifications();
        } finally {
            heartbeat.shutdownNow();
        }

    }


    @Scheduled(fixedDelayString = "${philter.worker.notification-interval-ms:5000}")
    public void reconcileNotifications() {
        try {
            for (final PendingDocumentEntity job : pendingDocumentDataService.pendingNotifications(100)) {
                try {
                    pendingDocumentDataService.recordNotificationAttempt(job.getId());
                    enqueueWebhook(job);
                    pendingDocumentDataService.acknowledgeNotification(job.getId());
                } catch (Exception ex) {
                    LOGGER.error("Notification intent retained for document {}", job.getDocumentId(), ex);
                }
            }
        } catch (Exception ex) {
            LOGGER.error("Notification reconciliation failed", ex);
        }
    }

    private void enqueueWebhook(final PendingDocumentEntity job) {
        final UserEntity user = userService.findOneById(job.getUserId());
        if (user == null || user.getWebhookUrl() == null || user.getWebhookUrl().isBlank()) return;
        if (user.getWebhookSecret() == null || user.getWebhookSecret().isBlank()) {
            throw new IllegalStateException("Webhook URL is configured without a secret.");
        }
        final String event = PendingDocumentEntity.STATUS_COMPLETE.equals(job.getStatus())
                ? WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE : WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_FAILED;
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", event);
        payload.put("documentId", job.getDocumentId());
        payload.put("fileName", job.getFileName());
        payload.put("status", job.getStatus());
        payload.put("timestamp", job.getCompletedAt().toInstant().toString());
        if (job.getErrorMessage() != null) payload.put("error", job.getErrorMessage());
        final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setId(job.getId());
        delivery.setUserId(job.getUserId());
        delivery.setDocumentId(job.getDocumentId());
        delivery.setEventType(event);
        delivery.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
        delivery.setUrl(user.getWebhookUrl());
        delivery.setSecret(user.getWebhookSecret());
        delivery.setPayload(gson.toJson(payload));
        delivery.setCreatedAt(job.getCompletedAt());
        delivery.setUpdatedAt(new Date());
        delivery.setNextAttemptAt(new Date());
        webhookDeliveryDataService.enqueueOnce(delivery);
    }
}
