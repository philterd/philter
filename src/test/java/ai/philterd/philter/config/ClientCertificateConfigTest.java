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
package ai.philterd.philter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCertificateConfigTest {

    private static TomcatServletWebServerFactory factory(final boolean enabled, final Ssl.ClientAuth clientAuth) {
        final Ssl ssl = new Ssl();
        ssl.setEnabled(enabled);
        ssl.setClientAuth(clientAuth);
        final TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        factory.setSsl(ssl);
        return factory;
    }

    private static void customize(final TomcatServletWebServerFactory factory, final String trustStore) {
        final MockEnvironment environment = new MockEnvironment();
        if (trustStore != null) {
            environment.setProperty("SSL_TRUSTSTORE", trustStore);
        }
        new ClientCertificateConfig(environment).customize(factory);
    }

    @Test
    void withoutClientAuthenticationNothingIsRequiredOrSet() {
        final TomcatServletWebServerFactory factory = factory(true, Ssl.ClientAuth.NONE);
        assertDoesNotThrow(() -> customize(factory, null));
        customize(factory, "/ssl/clients.p12");
        assertNull(factory.getSsl().getTrustStore(), "a trust store is only used for client certificates");
        assertDoesNotThrow(() -> customize(factory(false, Ssl.ClientAuth.NONE), null));
    }

    @Test
    void theTrustStoreIsSetForClientAuthentication() {
        for (final Ssl.ClientAuth clientAuth : new Ssl.ClientAuth[]{Ssl.ClientAuth.NEED, Ssl.ClientAuth.WANT}) {
            final TomcatServletWebServerFactory factory = factory(true, clientAuth);
            customize(factory, "/ssl/clients.p12");
            assertEquals("/ssl/clients.p12", factory.getSsl().getTrustStore());
        }
    }

    @Test
    void clientAuthenticationWithoutATrustStoreRefusesToStart() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> customize(factory(true, Ssl.ClientAuth.NEED), null));
        assertTrue(refused.getMessage().contains("SSL_TRUSTSTORE"), refused.getMessage());
        assertThrows(IllegalStateException.class, () -> customize(factory(true, Ssl.ClientAuth.WANT), " "));
    }

    @Test
    void clientAuthenticationWithoutHttpsRefusesToStart() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> customize(factory(false, Ssl.ClientAuth.NEED), "/ssl/clients.p12"));
        assertTrue(refused.getMessage().contains("SSL_ENABLED"), refused.getMessage());
    }

}
