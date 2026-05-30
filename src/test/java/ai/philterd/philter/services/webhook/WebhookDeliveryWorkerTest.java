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
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookDeliveryWorkerTest {

    @Mock private WebhookDeliveryDataService webhookDeliveryDataService;
    @Mock private WebhookService webhookService;

    private WebhookDeliveryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new WebhookDeliveryWorker(webhookDeliveryDataService, webhookService);
    }

    private WebhookDeliveryEntity delivery(final String url) {
        final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setId(new ObjectId());
        delivery.setUrl(url);
        delivery.setAttempts(1);
        return delivery;
    }

    @Test
    void noDueDeliveriesDoesNothing() throws Exception {
        when(webhookDeliveryDataService.claimNextDue(any())).thenReturn(null);

        worker.poll();

        verify(webhookService, never()).deliver(any());
        verify(webhookDeliveryDataService, never()).markDelivered(any());
        verify(webhookDeliveryDataService, never()).rescheduleOrFail(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void successfulDeliveryIsMarkedDelivered() throws Exception {
        final WebhookDeliveryEntity delivery = delivery("https://example.com/hook");

        // One due delivery, then the queue drains.
        when(webhookDeliveryDataService.claimNextDue(any())).thenReturn(delivery, (WebhookDeliveryEntity) null);

        worker.poll();

        verify(webhookService).deliver(delivery);
        verify(webhookDeliveryDataService).markDelivered(delivery.getId());
        verify(webhookDeliveryDataService, never()).rescheduleOrFail(any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void failedDeliveryIsRescheduled() throws Exception {
        final WebhookDeliveryEntity delivery = delivery("https://example.com/hook");

        when(webhookDeliveryDataService.claimNextDue(any())).thenReturn(delivery, (WebhookDeliveryEntity) null);
        doThrow(new RuntimeException("connection refused")).when(webhookService).deliver(delivery);

        worker.poll();

        verify(webhookDeliveryDataService, never()).markDelivered(any());
        verify(webhookDeliveryDataService).rescheduleOrFail(eq(delivery.getId()), eq(1), eq("connection refused"));
    }

    @Test
    void drainsMultipleDueDeliveriesInOnePoll() throws Exception {
        final WebhookDeliveryEntity first = delivery("https://example.com/a");
        final WebhookDeliveryEntity second = delivery("https://example.com/b");

        when(webhookDeliveryDataService.claimNextDue(any()))
                .thenReturn(first, second, (WebhookDeliveryEntity) null);

        worker.poll();

        verify(webhookService).deliver(first);
        verify(webhookService).deliver(second);
        verify(webhookDeliveryDataService, times(2)).markDelivered(any());
    }

    @Test
    void claimFailureDoesNotPropagate() {
        // A failure while claiming must be swallowed so the scheduled poller keeps running.
        when(webhookDeliveryDataService.claimNextDue(any())).thenThrow(new RuntimeException("mongo down"));

        worker.poll(); // must not throw
    }

}
