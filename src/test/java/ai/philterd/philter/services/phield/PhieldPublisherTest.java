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

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhieldPublisherTest {

    @Mock private AdminSettingsDataService adminSettingsDataService;

    private AdminSettingsEntity settings(final boolean enabled, final String url) {
        final AdminSettingsEntity s = new AdminSettingsEntity();
        s.setPhieldEnabled(enabled);
        s.setPhieldUrl(url);
        s.setPhieldSourceId("src");
        s.setPhieldOrganization("org");
        return s;
    }

    @Test
    void disabledWhenToggleOff() {
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings(false, "http://phield:8080"));
        assertFalse(new PhieldPublisher(adminSettingsDataService).isEnabled());
    }

    @Test
    void disabledWhenUrlBlankEvenIfToggleOn() {
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings(true, "  "));
        assertFalse(new PhieldPublisher(adminSettingsDataService).isEnabled());
    }

    @Test
    void enabledWhenToggleOnAndUrlSet() {
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings(true, "http://phield:8080"));
        assertTrue(new PhieldPublisher(adminSettingsDataService).isEnabled());
    }

    @Test
    void payloadContainsCountsAndMetadataOnly() {
        final PhieldPublisher publisher = new PhieldPublisher(adminSettingsDataService);

        final Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("SSN", 6);
        counts.put("EMAIL_ADDRESS", 110);

        final String payload = publisher.buildPayload("ctx-a", counts, "philter-1", "acme");

        assertTrue(payload.contains("\"source_id\":\"philter-1\""), payload);
        assertTrue(payload.contains("\"organization\":\"acme\""), payload);
        assertTrue(payload.contains("\"context\":\"ctx-a\""), payload);
        assertTrue(payload.contains("\"SSN\":6"), payload);
        assertTrue(payload.contains("\"EMAIL_ADDRESS\":110"), payload);
        // Privacy guarantee: only counts are sent.
        assertFalse(payload.contains("\"text\""), "payload must not contain span text");
        assertFalse(payload.contains("\"replacement\""), "payload must not contain replacements");
    }

    @Test
    void postsCountsToConfiguredIngestEndpointAndToleratesErrorStatus() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> path = new AtomicReference<>();
        final AtomicReference<String> body = new AtomicReference<>();

        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ingest", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(500, -1); // confirm a non-2xx status never breaks the caller
            exchange.close();
            latch.countDown();
        });
        server.start();

        try {
            when(adminSettingsDataService.findAdminSettings())
                    .thenReturn(settings(true, "http://127.0.0.1:" + server.getAddress().getPort()));

            final PhieldPublisher publisher = new PhieldPublisher(adminSettingsDataService);

            final Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("SSN", 3);

            publisher.publish("ctx", counts); // must not throw despite the 500

            assertTrue(latch.await(3, TimeUnit.SECONDS), "Phield should have received the request");
            org.junit.jupiter.api.Assertions.assertEquals("/ingest", path.get());
            assertTrue(body.get().contains("\"SSN\":3"), body.get());
            assertTrue(body.get().contains("\"context\":\"ctx\""), body.get());
            assertFalse(body.get().contains("\"text\""), "no PII text may be sent");
        } finally {
            server.stop(0);
        }
    }

}
