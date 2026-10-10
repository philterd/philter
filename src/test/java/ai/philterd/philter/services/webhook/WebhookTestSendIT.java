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
import ai.philterd.philter.data.services.AdminSettingsDataService;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** A test event goes out signed like a real delivery, and every outcome is reported rather than thrown. */
class WebhookTestSendIT {

    private static final String SECRET = "the-shared-secret-1234567890";
    private static final String PAYLOAD = "{\"event\":\"WEBHOOK_TEST\",\"test\":true}";

    private HttpServer receiver;
    private final AtomicInteger status = new AtomicInteger(204);
    private final AtomicInteger requests = new AtomicInteger();
    private final Map<String, String> received = new ConcurrentHashMap<>();

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            requests.incrementAndGet();
            received.put("event", exchange.getRequestHeaders().getFirst("X-Philter-Event"));
            received.put("deliveryId", exchange.getRequestHeaders().getFirst("X-Philter-Delivery-Id"));
            received.put("timestamp", exchange.getRequestHeaders().getFirst("X-Philter-Timestamp"));
            received.put("signature", exchange.getRequestHeaders().getFirst("X-Philter-Signature"));
            received.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    private static WebhookService.TestResult send(final String allowlist, final String url) throws Exception {
        try (final CloseableHttpClient client = HttpClients.custom().disableAutomaticRetries().build()) {
            return new WebhookService(client, () -> new WebhookDestinationPolicy(allowlist))
                    .test(url, "6a1106e9f5b4e90cb1d35a7c", PAYLOAD, SECRET);
        }
    }

    @Test
    @DisplayName("A test is sent signed, marked as a test event, and an accepting receiver is reported as delivered")
    void sendsASignedTestEvent() throws Exception {

        final WebhookService.TestResult result = send("127.0.0.1", url());

        assertTrue(result.delivered());
        assertEquals(204, result.statusCode());
        assertNull(result.error());
        assertEquals(WebhookDeliveryEntity.EVENT_WEBHOOK_TEST, received.get("event"));
        assertEquals("6a1106e9f5b4e90cb1d35a7c", received.get("deliveryId"));
        assertEquals(PAYLOAD, received.get("body"));
        final long timestamp = Long.parseLong(received.get("timestamp"));
        assertEquals("sha256=" + WebhookService.sign(timestamp, PAYLOAD, SECRET), received.get("signature"),
                "a receiver verifies a test exactly as it verifies a real delivery");

    }

    @Test
    @DisplayName("A receiver that refuses the test is reported with its status, not thrown")
    void reportsARefusingReceiver() throws Exception {

        status.set(500);

        final WebhookService.TestResult result = send("127.0.0.1", url());

        assertFalse(result.delivered());
        assertEquals(500, result.statusCode());
        assertEquals("Webhook responded with HTTP 500", result.error());

    }

    @Test
    @DisplayName("A host outside the administrator's allowlist is reported without any request being made")
    void reportsAHostOutsideTheAllowlist() throws Exception {

        final WebhookService.TestResult result = send("hooks.example.com", url());

        assertFalse(result.delivered());
        assertNull(result.statusCode());
        assertNotNull(result.error());
        assertTrue(result.error().contains("not permitted"), result.error());
        assertEquals(0, requests.get());

    }

    @Test
    @DisplayName("With no allowlist, a loopback receiver is refused when connecting, as the application's client does")
    void reportsALoopbackReceiverRefusedOnConnect() throws Exception {

        // As the application's httpClient bean resolves names: through the destination policy.
        final AdminSettingsDataService noAllowlist = mock(AdminSettingsDataService.class);
        try (final CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new WebhookDnsResolver(noAllowlist)).build())
                .disableAutomaticRetries().build()) {

            final WebhookService.TestResult result = new WebhookService(client, () -> new WebhookDestinationPolicy(null))
                    .test(url(), "6a1106e9f5b4e90cb1d35a7c", PAYLOAD, SECRET);

            assertFalse(result.delivered());
            assertNull(result.statusCode());
            assertTrue(result.error().contains("not permitted"), result.error());
            assertEquals(0, requests.get());
        }

    }

    @Test
    @DisplayName("A receiver that cannot be reached is reported with no status")
    void reportsAnUnreachableReceiver() throws Exception {

        final int closedPort;
        try (final ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        final WebhookService.TestResult result = send("127.0.0.1", "http://127.0.0.1:" + closedPort + "/hook");

        assertFalse(result.delivered());
        assertNull(result.statusCode());
        assertNotNull(result.error());

    }

}
