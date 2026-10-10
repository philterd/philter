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
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public class WebhookService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final HttpClient httpClient;
    private final Supplier<WebhookDestinationPolicy> destinationPolicy;

    public WebhookService(final HttpClient httpClient, final Supplier<WebhookDestinationPolicy> destinationPolicy) {
        this.httpClient = httpClient;
        this.destinationPolicy = destinationPolicy;
    }

    public void deliver(final WebhookDeliveryEntity delivery) throws Exception {

        final int code = post(delivery.getUrl(), delivery.getEventType(), delivery.getId().toHexString(),
                delivery.getPayload(), delivery.getSecret());
        if (code < 200 || code >= 300) {
            throw new WebhookDeliveryException("Webhook responded with HTTP " + code);
        }
        LOGGER.debug("Delivered webhook {} to {}: HTTP {}", delivery.getId(), delivery.getUrl(), code);

    }

    /**
     * Sends one signed test event now, outside the delivery queue, and reports what happened rather
     * than throwing: the receiver's status code, or why there was none.
     */
    public TestResult test(final String url, final String deliveryId, final String payload, final String secret) {

        final long started = System.nanoTime();
        try {
            final int code = post(url, WebhookDeliveryEntity.EVENT_WEBHOOK_TEST, deliveryId, payload, secret);
            final boolean delivered = code >= 200 && code < 300;
            return new TestResult(delivered, code, delivered ? null : "Webhook responded with HTTP " + code,
                    elapsedMillis(started));
        } catch (final Exception e) {
            final String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new TestResult(false, null, message, elapsedMillis(started));
        }

    }

    /** What a test delivery did: whether the receiver accepted it, its status code, and any error. */
    public record TestResult(boolean delivered, Integer statusCode, String error, long durationMillis) {
    }

    /** POSTs a signed event and returns the receiver's status code. */
    private int post(final String url, final String eventType, final String deliveryId, final String payload,
                     final String secret) throws Exception {

        // Re-checked here, not only when the URL was saved, so narrowing the allowlist takes effect on
        // destinations already configured.
        final String host = URI.create(url).getHost();

        if (!destinationPolicy.get().isHostAllowed(host)) {
            throw new WebhookDeliveryException("Delivery to " + host + " is not permitted by the webhook allowlist.");
        }

        final long timestamp = System.currentTimeMillis() / 1000L;
        final String signature = sign(timestamp, payload, secret);

        final HttpPost post = new HttpPost(url);
        post.setHeader("X-Philter-Event", eventType);
        post.setHeader("X-Philter-Delivery-Id", deliveryId);
        post.setHeader("X-Philter-Timestamp", Long.toString(timestamp));
        post.setHeader("X-Philter-Signature", "sha256=" + signature);
        post.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));

        final ClassicHttpResponse response = (ClassicHttpResponse) httpClient.executeOpen(null, post, null);
        try {
            return response.getCode();
        } finally {
            response.close();
        }

    }

    private static long elapsedMillis(final long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    public static String sign(final long timestampSeconds, final String payload, final String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("Webhook secret is required to sign payload.");
        }
        try {
            final Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            final String signedString = timestampSeconds + "." + payload;
            final byte[] hmac = mac.doFinal(signedString.getBytes(StandardCharsets.UTF_8));
            return toHex(hmac);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign webhook payload", ex);
        }
    }

    private static String toHex(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (final byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static class WebhookDeliveryException extends Exception {
        public WebhookDeliveryException(final String message) {
            super(message);
        }
    }

}
