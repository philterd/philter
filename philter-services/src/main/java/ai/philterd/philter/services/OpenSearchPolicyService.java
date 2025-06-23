package ai.philterd.philter.services;

import ai.philterd.phileas.model.policy.Policy;
import ai.philterd.phileas.model.services.PolicyService;
import ai.philterd.philter.PhilterConfiguration;
import com.google.gson.Gson;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.function.Factory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.nio.ssl.TlsStrategy;
import org.apache.hc.core5.reactor.ssl.TlsDetails;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OpenSearchPolicyService implements PolicyService {

    private final OpenSearchClient client;
    private final String index = "policies";
    private final Gson gson;

    public OpenSearchPolicyService(final PhilterConfiguration philterConfiguration) throws Exception {

        this.gson = new Gson();

        //System.setProperty("javax.net.ssl.trustStore", "/full/path/to/keystore");
        //System.setProperty("javax.net.ssl.trustStorePassword", "password-to-keystore");

        final HttpHost host = new HttpHost("https", "localhost", 9200);
        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();

        // Only for demo purposes. Don't specify your credentials in code.
     //   credentialsProvider.setCredentials(new AuthScope(host), new UsernamePasswordCredentials("admin", "admin".toCharArray()));

        /*final SSLContext sslcontext = SSLContextBuilder
                .create()
                .loadTrustMaterial(null, (chains, authType) -> true)
                .build();*/

        final ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
//            final TlsStrategy tlsStrategy = ClientTlsStrategyBuilder.create()
//                    .setSslContext(sslcontext)
//                    .setTlsDetailsFactory(new Factory<SSLEngine, TlsDetails>() {
//                        @Override
//                        public TlsDetails create(final SSLEngine sslEngine) {
//                            return new TlsDetails(sslEngine.getSession(), sslEngine.getApplicationProtocol());
//                        }
//                    })
//                    .build();

            final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                    .create()
         //           .setTlsStrategy(tlsStrategy)
                    .build();

            return httpClientBuilder
         //           .setDefaultCredentialsProvider(credentialsProvider)
                    .setConnectionManager(connectionManager);

        });

        final OpenSearchTransport transport = builder.build();
        this.client = new OpenSearchClient(transport);

        // TODO: Make sure the index does not already exist.
        // TODO: Create a mapping.
        final CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder().index(index).build();
        client.indices().create(createIndexRequest);

    }

    @Override
    public List<String> get() throws IOException {
        return List.of();
    }

    @Override
    public String get(final String name) throws IOException {
        return "";
    }

    @Override
    public Map<String, String> getAll() throws IOException {

        final SearchResponse<Policy> searchResponse = client.search(s -> s.index(index), Policy.class);
        for (int i = 0; i< searchResponse.hits().hits().size(); i++) {
            System.out.println(searchResponse.hits().hits().get(i).source());
        }

        return Map.of();
    }

    @Override
    public void save(final String json) throws IOException {

        final String id = UUID.randomUUID().toString();

        final Policy policy = gson.fromJson(json, Policy.class);
        final IndexRequest<Policy> indexRequest = new IndexRequest.Builder<Policy>().index(index).id(id).document(policy).build();

        client.index(indexRequest);

    }

    @Override
    public void delete(final String id) throws IOException {
        client.delete(b -> b.index(index).id(id));
    }

}
