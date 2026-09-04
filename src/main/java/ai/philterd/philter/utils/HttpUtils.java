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
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;

public class HttpUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpUtils.class);

    private static final AtomicBoolean TRUST_ALL_WARNED = new AtomicBoolean(false);

    private HttpUtils() {
        // This is a utility class.
    }

    /**
     * Builds the connection manager for the redaction pipeline's outbound HTTPS calls: verifying by
     * default, trusting any certificate only when {@code TLS_TRUST_ALL_ENABLED} is set.
     */
    public static PoolingHttpClientConnectionManagerBuilder getPoolingHttpClientConnectionManagerBuilder()
            throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {

        if (!TlsVerificationConfig.isTrustAllEnabled()) {
            return PoolingHttpClientConnectionManagerBuilder.create();
        }

        warnOnceAboutTrustAll();

        return getTrustAllPoolingHttpClientConnectionManagerBuilder();

    }

    private static void warnOnceAboutTrustAll() {
        if (TRUST_ALL_WARNED.compareAndSet(false, true)) {
            LOGGER.warn("TLS_TRUST_ALL_ENABLED is ON: outbound HTTPS from the redaction pipeline accepts "
                    + "ANY certificate from ANY host, so those connections are interceptable by anything "
                    + "on the path. Use this only for a self-signed service on a private network, and "
                    + "prefer adding its issuer to the JVM truststore instead.");
        }
    }

    /**
     * Creates an {@link SSLConnectionSocketFactory} that trusts all certificates (including self-signed).
     * @return An {@link SSLConnectionSocketFactory}.
     * @throws NoSuchAlgorithmException Thrown if the algorithm is not available.
     * @throws KeyStoreException Thrown if there is a problem with the key store.
     * @throws KeyManagementException Thrown if there is a problem with key management.
     */
    public static SSLConnectionSocketFactory getTrustAllSslConnectionSocketFactory() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {

        final SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(new TrustAllStrategy())
                .build();

        return SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                .build();

    }

    /**
     * Creates a {@link PoolingHttpClientConnectionManagerBuilder} pre-configured to trust all certificates.
     * @return A {@link PoolingHttpClientConnectionManagerBuilder}.
     * @throws NoSuchAlgorithmException Thrown if the algorithm is not available.
     * @throws KeyStoreException Thrown if there is a problem with the key store.
     * @throws KeyManagementException Thrown if there is a problem with key management.
     */
    public static PoolingHttpClientConnectionManagerBuilder getTrustAllPoolingHttpClientConnectionManagerBuilder() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {

        return PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(getTrustAllSslConnectionSocketFactory());

    }

}
