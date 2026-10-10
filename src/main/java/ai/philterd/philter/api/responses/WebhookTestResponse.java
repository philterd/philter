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

/** The outcome of sending a test event to the webhook. */
public class WebhookTestResponse {

    private final boolean delivered;
    private final Integer statusCode;
    private final String error;
    private final long durationMillis;
    private final String deliveryId;

    public WebhookTestResponse(final boolean delivered, final Integer statusCode, final String error,
                               final long durationMillis, final String deliveryId) {
        this.delivered = delivered;
        this.statusCode = statusCode;
        this.error = error;
        this.durationMillis = durationMillis;
        this.deliveryId = deliveryId;
    }

    @Schema(description = "Whether the receiver answered with a 2xx status.")
    public boolean isDelivered() { return delivered; }

    @Schema(description = "The status the receiver answered with, or null when there was no answer, such as when the "
            + "connection failed, timed out, or the destination is not permitted.")
    public Integer getStatusCode() { return statusCode; }

    @Schema(description = "Why the test was not delivered, or null when it was.")
    public String getError() { return error; }

    @Schema(description = "How long the attempt took, in milliseconds.")
    public long getDurationMillis() { return durationMillis; }

    @Schema(description = "The test event's X-Philter-Delivery-Id, to find it in the receiver's logs.")
    public String getDeliveryId() { return deliveryId; }

}
