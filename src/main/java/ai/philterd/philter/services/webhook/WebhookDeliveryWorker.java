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
package ai.philterd.philter.services.webhook;

import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class WebhookDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookDeliveryWorker.class);

    private final WebhookDeliveryDataService webhookDeliveryDataService;
    private final WebhookService webhookService;

    public WebhookDeliveryWorker(final WebhookDeliveryDataService webhookDeliveryDataService,
                                 final WebhookService webhookService) {
        this.webhookDeliveryDataService = webhookDeliveryDataService;
        this.webhookService = webhookService;
    }

    @Scheduled(fixedDelayString = "${philter.webhook.poll-interval-ms:5000}", initialDelay = 7000)
    public void poll() {

        try {
            WebhookDeliveryEntity delivery;
            while ((delivery = webhookDeliveryDataService.claimNextDue(new Date())) != null) {
                deliver(delivery);
            }
        } catch (Exception ex) {
            LOGGER.error("Webhook delivery poll failed", ex);
        }

    }

    private void deliver(final WebhookDeliveryEntity delivery) {

        try {
            webhookService.deliver(delivery);
            webhookDeliveryDataService.markDelivered(delivery.getId());
            LOGGER.info("Delivered webhook {} to {} (attempt {})",
                    delivery.getId(), delivery.getUrl(), delivery.getAttempts());
        } catch (Exception ex) {
            final String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            LOGGER.warn("Webhook delivery {} to {} failed on attempt {}: {}",
                    delivery.getId(), delivery.getUrl(), delivery.getAttempts(), message);
            webhookDeliveryDataService.rescheduleOrFail(delivery.getId(), delivery.getAttempts(), message);
        }

    }

}
