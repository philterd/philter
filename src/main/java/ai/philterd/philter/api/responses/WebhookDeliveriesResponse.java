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
package ai.philterd.philter.api.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A page of the user's webhook deliveries, newest first, and how many there are. */
public class WebhookDeliveriesResponse {

    private final List<WebhookDeliveryView> deliveries;
    private final long total;

    public WebhookDeliveriesResponse(final List<WebhookDeliveryView> deliveries, final long total) {
        this.deliveries = deliveries;
        this.total = total;
    }

    @Schema(description = "The deliveries on this page, newest first.")
    public List<WebhookDeliveryView> getDeliveries() { return deliveries; }

    @Schema(description = "How many deliveries the user has.")
    public long getTotal() { return total; }

}
