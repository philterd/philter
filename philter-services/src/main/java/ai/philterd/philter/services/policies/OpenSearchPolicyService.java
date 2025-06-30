package ai.philterd.philter.services.policies;

import ai.philterd.phileas.model.policy.Policy;
import ai.philterd.philter.PhilterConfiguration;
import com.google.gson.Gson;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OpenSearchPolicyService implements PolicyService {

    private static final Logger LOGGER = LogManager.getLogger(OpenSearchPolicyService.class);

    private final OpenSearchClient client;
    private final String index = "policies";
    private final Gson gson;

    public OpenSearchPolicyService(final PhilterConfiguration philterConfiguration) throws Exception {

        LOGGER.info("Initializing the OpenSearch policy service.");

        this.gson = new Gson();

        final String opensearchScheme = philterConfiguration.opensearchScheme();
        final String opensearchHost = philterConfiguration.opensearchHost();
        final int opensearchPort = philterConfiguration.opensearchPort();
        final HttpHost host = new HttpHost(opensearchScheme, opensearchHost, opensearchPort);

        final ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);
        builder.setHttpClientConfigCallback(httpClientBuilder -> {

            final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder
                    .create()
                    .build();

            return httpClientBuilder
                    .setConnectionManager(connectionManager);

        });

        final OpenSearchTransport transport = builder.build();
        this.client = new OpenSearchClient(transport);

        // Create the index if it does not already exist.
        if(!doesIndexExist()) {
            final CreateIndexRequest createIndexRequest = new CreateIndexRequest.Builder().index(index).build();
            client.indices().create(createIndexRequest);
        }

    }

    @Override
    public List<String> get() throws IOException {

        final List<String> policies = new LinkedList<>();
        int pageSize = 100;
        int from = 0;
        long totalHits = 0;
        boolean firstRun = true;

        do {

            int finalFrom = from;
            SearchResponse<Policy> searchResponse = client.search(s -> s
                            .index(index)
                            .query(q -> q.matchAll(ma -> ma))
                            .size(pageSize)
                            .from(finalFrom),
                    Policy.class
            );

            List<Hit<Policy>> hits = searchResponse.hits().hits();
            if (firstRun) {
                totalHits = searchResponse.hits().total() != null ? searchResponse.hits().total().value() : 0;
                firstRun = false;
            }

            for (final Hit<Policy> hit : hits) {
                final Policy policy = hit.source();
                if (policy != null) {
                    policies.add(gson.toJson(policy));
                }
            }

            from += hits.size();

            if (hits.isEmpty() || from >= totalHits) {
                break;
            }

        } while (true);

        return policies;

    }

    @Override
    public Policy get(final String name) throws IOException {

        final SearchResponse<Policy> searchResponse = client.search(s -> s
                        .index(index)
                        .query(q -> q
                                .match(m -> m
                                        .field("name")
                                        .query(FieldValue.of(name))
                                )
                        )
                        .size(1),
                Policy.class
        );

        final List<Hit<Policy>> hits = searchResponse.hits().hits();

        if (!hits.isEmpty()) {

            return hits.get(0).source();

        } else {
            return null;
        }

    }

    @Override
    public Map<String, Policy> getAll() throws IOException {

        final SearchResponse<Policy> searchResponse = client.search(s -> s.index(index), Policy.class);
        for (int i = 0; i< searchResponse.hits().hits().size(); i++) {
            System.out.println(searchResponse.hits().hits().get(i).source());
        }

        return Map.of();
    }

    @Override
    public void save(final Policy policy) throws IOException {

        final String id = UUID.randomUUID().toString();

        final IndexRequest<Policy> indexRequest = new IndexRequest.Builder<Policy>().index(index).id(id).document(policy).build();

        client.index(indexRequest);

    }

    @Override
    public void delete(final String id) throws IOException {
        client.delete(b -> b.index(index).id(id));
    }

    private boolean doesIndexExist() throws IOException {
        return client.indices().exists(b -> b.index(index)).value();
    }

}
