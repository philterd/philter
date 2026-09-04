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
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A receiver that accepts a connection and never answers is the case Apache HttpClient does not bound
 * by default. Left unbounded it holds the delivery thread indefinitely, and that thread is shared with
 * the redaction worker.
 */
class WebhookTimeoutIT {

    private static final int RESPONSE_TIMEOUT_SECONDS = 2;

    private ServerSocket serverSocket;
    private Thread acceptor;
    private final List<Socket> accepted = new ArrayList<>();
    private final CountDownLatch connected = new CountDownLatch(1);

    @BeforeEach
    void startSilentServer() throws Exception {
        serverSocket = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    // Accept, hold the socket open, and never write a response.
                    accepted.add(serverSocket.accept());
                    connected.countDown();
                } catch (final IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopSilentServer() throws Exception {
        for (final Socket socket : accepted) {
            socket.close();
        }
        serverSocket.close();
        acceptor.join(TimeUnit.SECONDS.toMillis(5));
    }

    /** Built the way the application's httpClient bean is, with a shorter response timeout. */
    private static CloseableHttpClient client() {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                        .build())
                .disableAutomaticRetries()
                .build();
    }

    private WebhookDeliveryEntity delivery() {
        final WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setId(new ObjectId());
        entity.setUrl("http://127.0.0.1:" + serverSocket.getLocalPort() + "/hook");
        entity.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
        entity.setPayload("{\"event\":\"document.redaction.complete\"}");
        entity.setSecret("the-shared-secret-1234567890");
        entity.setCreatedAt(new Date());
        return entity;
    }

    @Test
    @DisplayName("Delivery to a receiver that never responds aborts instead of hanging")
    void deliveryToASilentReceiverAborts() throws Exception {

        final long start = System.nanoTime();

        try (final CloseableHttpClient httpClient = client()) {
            assertThrows(IOException.class, () -> new WebhookService(httpClient).deliver(delivery()),
                    "a silent receiver must abort the delivery, not hold the thread");
        }

        final long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

        assertTrue(connected.await(5, TimeUnit.SECONDS), "the request must have reached the server");
        assertTrue(elapsedSeconds < RESPONSE_TIMEOUT_SECONDS + 8,
                "delivery must abort near the response timeout; took " + elapsedSeconds + "s");

    }

}
