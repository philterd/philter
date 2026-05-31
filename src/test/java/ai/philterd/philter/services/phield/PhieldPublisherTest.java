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
package ai.philterd.philter.services.phield;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhieldPublisherTest {

    @Test
    void disabledWhenNoUrlConfigured() {
        assertFalse(new PhieldPublisher("", "philter", "philter").isEnabled(),
                "publishing must be disabled when PHIELD_URL is not set");
        assertFalse(new PhieldPublisher("   ", "philter", "philter").isEnabled(),
                "a blank PHIELD_URL must leave publishing disabled");
    }

    @Test
    void enabledWhenUrlConfigured() {
        assertTrue(new PhieldPublisher("http://phield:8080", "philter", "philter").isEnabled());
    }

    @Test
    void payloadContainsCountsAndMetadataOnly() {
        final PhieldPublisher publisher = new PhieldPublisher("http://phield:8080", "philter-1", "acme");

        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("SSN", 6);
        counts.put("EMAIL_ADDRESS", 110);

        final String payload = publisher.buildPayload("ctx-a", counts);

        // The metadata and the per-type counts are present.
        assertTrue(payload.contains("\"source_id\":\"philter-1\""), payload);
        assertTrue(payload.contains("\"organization\":\"acme\""), payload);
        assertTrue(payload.contains("\"context\":\"ctx-a\""), payload);
        assertTrue(payload.contains("\"pii_types\""), payload);
        assertTrue(payload.contains("\"SSN\":6"), payload);
        assertTrue(payload.contains("\"EMAIL_ADDRESS\":110"), payload);

        // Privacy guarantee: only counts are sent. The payload carries no span text/replacement
        // fields, so no PII can leak through it.
        assertFalse(payload.contains("\"text\""), "payload must not contain span text");
        assertFalse(payload.contains("\"replacement\""), "payload must not contain replacements");
    }

    @Test
    void postsCountsToIngestAndToleratesErrorStatus() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<String> body = new AtomicReference<>();

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            // Respond with a server error to confirm a non-2xx status never breaks the caller.
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();

        try {
            final PhieldPublisher publisher = new PhieldPublisher("http://127.0.0.1:" + server.getAddress().getPort(), "src", "org");

            final Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("SSN", 3);

            // Must not throw even though the server returns 500 (fire-and-forget).
            publisher.publish("ctx", counts);

            assertTrue(latch.await(3, TimeUnit.SECONDS), "Phield should have received the request");
            assertEquals("/ingest", path.get(), "counts must be posted to the /ingest endpoint");
            assertTrue(body.get().contains("\"SSN\":3"), "the counts must be sent: " + body.get());
            assertTrue(body.get().contains("\"context\":\"ctx\""), body.get());
            assertFalse(body.get().contains("\"text\""), "no PII text may be sent");
        } finally {
            server.stop(0);
        }
    }

}
