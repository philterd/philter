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
import java.nio.charset.StandardCharsets;

public class WebhookService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebhookService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final HttpClient httpClient;

    public WebhookService(final HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void deliver(final WebhookDeliveryEntity delivery) throws Exception {

        final long timestamp = System.currentTimeMillis() / 1000L;
        final String signature = sign(timestamp, delivery.getPayload(), delivery.getSecret());

        final HttpPost post = new HttpPost(delivery.getUrl());
        post.setHeader("X-Philter-Event", delivery.getEventType());
        post.setHeader("X-Philter-Delivery-Id", delivery.getId().toHexString());
        post.setHeader("X-Philter-Timestamp", Long.toString(timestamp));
        post.setHeader("X-Philter-Signature", "sha256=" + signature);
        post.setEntity(new StringEntity(delivery.getPayload(), ContentType.APPLICATION_JSON));

        final ClassicHttpResponse response = (ClassicHttpResponse) httpClient.executeOpen(null, post, null);
        try {
            final int code = response.getCode();
            if (code < 200 || code >= 300) {
                throw new WebhookDeliveryException("Webhook responded with HTTP " + code);
            }
            LOGGER.debug("Delivered webhook {} to {}: HTTP {}", delivery.getId(), delivery.getUrl(), code);
        } finally {
            response.close();
        }

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
