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

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * Sets the trust store for client certificates from {@code SSL_TRUSTSTORE}, and refuses to start when
 * {@code SSL_CLIENT_AUTH} asks for client certificates that could not be checked as configured: with HTTPS off, no
 * certificate is requested at all, and with no trust store the JVM's default one would vouch for certificates from any
 * public certificate authority.
 */
@Configuration
public class ClientCertificateConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final Environment environment;

    public ClientCertificateConfig(final Environment environment) {
        this.environment = environment;
    }

    @Override
    public void customize(final TomcatServletWebServerFactory factory) {
        final Ssl ssl = factory.getSsl();
        final String trustStore = environment.getProperty("SSL_TRUSTSTORE");
        final boolean clientAuth = ssl != null && ssl.getClientAuth() != null && ssl.getClientAuth() != Ssl.ClientAuth.NONE;
        if (!clientAuth) {
            return;
        }
        if (!ssl.isEnabled()) {
            throw new IllegalStateException("SSL_CLIENT_AUTH requires SSL_ENABLED=true.");
        }
        if (!StringUtils.hasText(trustStore) && !StringUtils.hasText(ssl.getTrustStore())) {
            throw new IllegalStateException("SSL_CLIENT_AUTH requires SSL_TRUSTSTORE, the trust store holding the "
                    + "certificates that issue client certificates.");
        }
        if (StringUtils.hasText(trustStore)) {
            ssl.setTrustStore(trustStore);
        }
    }

}
