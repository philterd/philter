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
package ai.philterd.philter.utils;

import ai.philterd.philter.config.TlsVerificationConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.KeyManagerFactory;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A real TLS handshake against a self-signed server, rather than inspecting the builder. */
class HttpUtilsTest {

    @AfterEach
    void clearOverride() {
        TlsVerificationConfig.setOverrideForTesting(null);
    }

    /** Presents a self-signed certificate no truststore knows. */
    private static SSLServerSocket selfSignedServer() throws Exception {
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (final var in = HttpUtilsTest.class.getResourceAsStream("/self-signed.p12")) {
            assertNotNull(in, "the test keystore must be on the classpath");
            keyStore.load(in, "philter".toCharArray());
        }

        final KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, "philter".toCharArray());

        final SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        final SSLServerSocketFactory factory = sslContext.getServerSocketFactory();
        return (SSLServerSocket) factory.createServerSocket(0);
    }

    /** Completes the handshake, so the client's own trust decision decides. */
    private static Thread accept(final SSLServerSocket server) {
        final Thread thread = new Thread(() -> {
            try (final Socket socket = server.accept()) {
                socket.getInputStream().read();
            } catch (final IOException ignored) {
                // The client rejecting our certificate closes the socket. That is the point.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static CloseableHttpClient client() throws Exception {
        final PoolingHttpClientConnectionManager manager =
                HttpUtils.getPoolingHttpClientConnectionManagerBuilder().build();
        return HttpClients.custom().setConnectionManager(manager).disableAutomaticRetries().build();
    }

    @Test
    @DisplayName("By default an untrusted certificate is refused")
    void byDefaultAnUntrustedCertificateIsRefused() throws Exception {

        TlsVerificationConfig.setOverrideForTesting(false);

        try (final SSLServerSocket server = selfSignedServer()) {
            final Thread acceptor = accept(server);
            final String url = "https://localhost:" + server.getLocalPort() + "/";

            try (final CloseableHttpClient httpClient = client()) {
                assertThrows(SSLException.class,
                        () -> httpClient.execute(new org.apache.hc.client5.http.classic.methods.HttpGet(url),
                                response -> null),
                        "a self-signed certificate must not be trusted by default");
            }

            acceptor.join(TimeUnit.SECONDS.toMillis(5));
        }

    }

    @Test
    @DisplayName("With TLS_TRUST_ALL_ENABLED the same certificate is accepted")
    void withTrustAllEnabledTheSameCertificateIsAccepted() throws Exception {

        TlsVerificationConfig.setOverrideForTesting(true);

        try (final SSLServerSocket server = selfSignedServer()) {
            final Thread acceptor = accept(server);
            final String url = "https://localhost:" + server.getLocalPort() + "/";

            try (final CloseableHttpClient httpClient = client()) {
                // Anything but an SSLException means the handshake succeeded.
                final Exception thrown = assertThrows(Exception.class,
                        () -> httpClient.execute(new org.apache.hc.client5.http.classic.methods.HttpGet(url),
                                response -> null));
                org.junit.jupiter.api.Assertions.assertFalse(thrown instanceof SSLException,
                        "the certificate must be trusted when the switch is on, but got: " + thrown);
            }

            acceptor.join(TimeUnit.SECONDS.toMillis(5));
        }

    }

}
