package ai.philterd.philter.utils;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

public class HttpUtils {

    private HttpUtils() {
        // This is a utility class.
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
