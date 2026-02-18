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
package ai.philterd.philter.services.usage;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.opensearch.client.transport.endpoints.BooleanResponse;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class AbstractUsageService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractUsageService.class);

    private static final String OPENSEARCH_HOST = System.getenv().getOrDefault("OPENSEARCH_HOST", "localhost");
    private static final int OPENSEARCH_PORT = Integer.parseInt(System.getenv().getOrDefault("OPENSEARCH_PORT", "9200"));
    private static final String OPENSEARCH_SCHEME = System.getenv().getOrDefault("OPENSEARCH_SCHEME", "http");
    private static final String OPENSEARCH_USERNAME = System.getenv().getOrDefault("OPENSEARCH_USERNAME", "");
    private static final String OPENSEARCH_PASSWORD = System.getenv().getOrDefault("OPENSEARCH_PASSWORD", "");

    public static final String REDACTIONS_USAGE_INDEX_NAME = System.getenv().getOrDefault("REDACTIONS_INDEX_NAME", "pds-redactions");
    public static final String API_REQUESTS_USAGE_INDEX_NAME = System.getenv().getOrDefault("API_REQUESTS_INDEX_NAME", "pds-api-requests");

    protected final OpenSearchClient client;
    protected final String indexName;

    public AbstractUsageService(final String indexName) {

        this.indexName = indexName;

        LOGGER.info("Connecting to OpenSearch: {}://{}:{}", OPENSEARCH_SCHEME, OPENSEARCH_HOST, OPENSEARCH_PORT);

        final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(OPENSEARCH_USERNAME, OPENSEARCH_PASSWORD));

        final RestClient restClient = RestClient.builder(new HttpHost(OPENSEARCH_HOST, OPENSEARCH_PORT, OPENSEARCH_SCHEME))
                .setHttpClientConfigCallback(httpClientBuilder -> {
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                    httpClientBuilder.setDefaultRequestConfig(
                            RequestConfig.custom()
                                    .setConnectionRequestTimeout(5000)  // 5 seconds
                                    .setSocketTimeout(60000)        // 60 seconds
                                    .setConnectionRequestTimeout(5000) // 1 second
                                    .build()
                    );

                    return httpClientBuilder;
                })
                .build();

        final RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());

        this.client = new OpenSearchClient(transport);

    }

    private void createIndex() throws IOException {

        if(indexName.equals(REDACTIONS_USAGE_INDEX_NAME)) {

            final ExistsRequest existsRequest = new ExistsRequest.Builder().index(REDACTIONS_USAGE_INDEX_NAME).build();
            final BooleanResponse booleanResponse = client.indices().exists(existsRequest);

            if(!booleanResponse.value()) {

                LOGGER.info("Creating OpenSearch index: " + REDACTIONS_USAGE_INDEX_NAME);

                final TypeMapping mapping = new TypeMapping.Builder()
                        .properties("user_id", p -> p.keyword(k -> k))
                        .properties("timestamp", p -> p.date(d -> d))
                        .properties("type", p -> p.keyword(k -> k))
                        .properties("tokens", p -> p.integer(k -> k))
                        .properties("redactions", p -> p.integer(k -> k))
                        .properties("document_id", p -> p.keyword(k -> k))
                        .properties("context", p -> p.keyword(k -> k))
                        .properties("counts", p -> p.object(o -> o.enabled(false)))
                        .build();

                final CreateIndexRequest request = new CreateIndexRequest.Builder()
                        .index(REDACTIONS_USAGE_INDEX_NAME)
                        //  .settings(settings)
                        .mappings(mapping)
                        .build();

                client.indices().create(request);

            }

        } else if(indexName.equals(API_REQUESTS_USAGE_INDEX_NAME)) {

            final ExistsRequest existsRequest = new ExistsRequest.Builder().index(API_REQUESTS_USAGE_INDEX_NAME).build();
            final BooleanResponse booleanResponse = client.indices().exists(existsRequest);

            if(!booleanResponse.value()) {

                LOGGER.info("Creating OpenSearch index: {}", API_REQUESTS_USAGE_INDEX_NAME);

                final TypeMapping mapping = new TypeMapping.Builder()
                        .properties("user_id", p -> p.keyword(k -> k))
                        .properties("timestamp", p -> p.date(d -> d))
                        .properties("endpoint", p -> p.keyword(k -> k))
                        .properties("premium", p -> p.boolean_(k -> k))
                        .build();

                final CreateIndexRequest request = new CreateIndexRequest.Builder()
                        .index(API_REQUESTS_USAGE_INDEX_NAME)
                        //  .settings(settings)
                        .mappings(mapping)
                        .build();

                client.indices().create(request);

            }


        }

    }

    public void index(final Map<String, Object> document) throws IOException {

        createIndex();

        final IndexRequest<Map<String, Object>> indexRequest = new IndexRequest.Builder<Map<String, Object>>()
                .index(indexName)
                .id(UUID.randomUUID().toString())
                .document(document)
                .build();

        client.index(indexRequest);

    }

    public List<Integer> bulkIndex(final List<Map<String, Object>> documents) throws IOException {

        createIndex();

        final BulkRequest.Builder br = new BulkRequest.Builder();

        for (final Map<String, Object> document : documents) {

            br.operations(op -> op
                    .index(idx -> idx
                            .index(indexName)
                            .id(UUID.randomUUID().toString())
                            .document(document)
                    )
            );

        }

        final BulkResponse bulkResponse = client.bulk(br.build());

        final List<Integer> failedDocumentIndexes = new ArrayList<>();

        if(bulkResponse.errors()) {

            final List<BulkResponseItem> items = bulkResponse.items();

            for(int i = 0; i < bulkResponse.items().size(); i++) {

                final BulkResponseItem item = items.get(i);

                if(item.error() != null) {
                    failedDocumentIndexes.add(i);
                }

                LOGGER.error("Unable to index: {}", item.error().reason());

            }

        }

        return failedDocumentIndexes;

    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter LOCAL_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(java.time.ZoneOffset.UTC);

    public static String getNow() {
        return toIsoString(System.currentTimeMillis());
    }

    /**
     * Converts a long (System.currentTimeMillis()) to strict_date_optional_time string
     */
    public static String toIsoString(final long epochMillis) {
        return FORMATTER.format(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * Converts a LocalDate to strict_date_time_no_millis string at the start of the day.
     */
    public static String toIsoString(final LocalDate date) {
        if (date == null) return null;
        return LOCAL_DATE_FORMATTER.format(date.atStartOfDay());
    }

    /**
     * Converts a LocalDate to strict_date_time_no_millis string at the end of the day.
     */
    public static String toEndOfDayIsoString(final LocalDate date) {
        if (date == null) return null;
        return LOCAL_DATE_FORMATTER.format(date.atTime(23, 59, 59));
    }

}
