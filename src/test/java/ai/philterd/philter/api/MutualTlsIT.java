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
package ai.philterd.philter.api;

import ai.philterd.philter.testutil.InMemoryTestConfiguration;
import ai.philterd.philter.testutil.TestCertificates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Mutual TLS with SSL_CLIENT_AUTH=need: only a client presenting a certificate the trust store vouches for is served. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class MutualTlsIT {

    /** Nested, not imported, so its beans override the application's. See ApiFilterChainIT. */
    @TestConfiguration
    static class Config extends InMemoryTestConfiguration {
    }

    private static final TestCertificates CERTIFICATES = TestCertificates.create();

    /** Set through the same environment variables a deployment uses. */
    @DynamicPropertySource
    static void tls(final DynamicPropertyRegistry registry) {
        registry.add("SSL_ENABLED", () -> "true");
        registry.add("SSL_KEYSTORE", CERTIFICATES.serverKeyStore::toString);
        registry.add("SSL_KEYSTORE_PASSWORD", () -> TestCertificates.PASSWORD);
        registry.add("SSL_CLIENT_AUTH", () -> "need");
        registry.add("SSL_TRUSTSTORE", CERTIFICATES.caTrustStore::toString);
        registry.add("SSL_TRUSTSTORE_PASSWORD", () -> TestCertificates.PASSWORD);
    }

    @Autowired
    private Environment environment;

    /** Sends a GET over HTTPS, presenting the certificate in {@code clientKeyStore}, or none when it is null. */
    private int get(final Path clientKeyStore, final String path) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().sslContext(CERTIFICATES.clientContext(clientKeyStore)).build()) {
            final URI uri = URI.create("https://localhost:"
                    + environment.getRequiredProperty("local.server.port", Integer.class) + path);
            return client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    @DisplayName("A client presenting a certificate issued by the trusted CA is served")
    void aTrustedClientCertificateIsServed() throws Exception {
        assertEquals(200, get(CERTIFICATES.clientKeyStore, "/api/health"));
    }

    @Test
    @DisplayName("A client presenting no certificate is refused during the TLS handshake")
    void noClientCertificateIsRefused() {
        assertThrows(IOException.class, () -> get(null, "/api/health"));
    }

    @Test
    @DisplayName("A client presenting a certificate the trust store does not vouch for is refused")
    void anUntrustedClientCertificateIsRefused() {
        assertThrows(IOException.class, () -> get(CERTIFICATES.untrustedClientKeyStore, "/api/health"));
    }

    @Test
    @DisplayName("A trusted client certificate does not replace an API key")
    void aClientCertificateIsNotAnApiKey() throws Exception {
        assertEquals(401, get(CERTIFICATES.clientKeyStore, "/api/policies"));
    }
}
