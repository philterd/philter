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

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookServiceTest {

    private static final String SECRET = "the-shared-secret-1234567890";
    private static final String PAYLOAD = "{\"event\":\"DOCUMENT_REDACTION_COMPLETE\",\"documentId\":\"abc\"}";

    @Test
    void signIsDeterministicForSameInputs() {
        final long ts = 1_700_000_000L;
        final String a = WebhookService.sign(ts, PAYLOAD, SECRET);
        final String b = WebhookService.sign(ts, PAYLOAD, SECRET);
        assertEquals(a, b);
    }

    @Test
    void signProducesValidHmacSha256Hex() throws Exception {
        final long ts = 1_700_000_000L;
        final String signed = WebhookService.sign(ts, PAYLOAD, SECRET);

        final Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        final byte[] expected = mac.doFinal((ts + "." + PAYLOAD).getBytes(StandardCharsets.UTF_8));

        final StringBuilder hex = new StringBuilder();
        for (final byte b : expected) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(hex.toString(), signed);
    }

    @Test
    void differentTimestampsProduceDifferentSignatures() {
        final String a = WebhookService.sign(1_700_000_000L, PAYLOAD, SECRET);
        final String b = WebhookService.sign(1_700_000_001L, PAYLOAD, SECRET);
        assertNotEquals(a, b, "Anti-replay requires the timestamp to be bound into the signature");
    }

    @Test
    void differentPayloadsProduceDifferentSignatures() {
        final long ts = 1_700_000_000L;
        final String a = WebhookService.sign(ts, PAYLOAD, SECRET);
        final String b = WebhookService.sign(ts, PAYLOAD + " ", SECRET);
        assertNotEquals(a, b);
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        final long ts = 1_700_000_000L;
        final String a = WebhookService.sign(ts, PAYLOAD, SECRET);
        final String b = WebhookService.sign(ts, PAYLOAD, SECRET + "x");
        assertNotEquals(a, b);
    }

    @Test
    void missingSecretIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> WebhookService.sign(1L, PAYLOAD, null));
        assertThrows(IllegalArgumentException.class,
                () -> WebhookService.sign(1L, PAYLOAD, ""));
    }

}
