/*
 *     Copyright 2025 Philterd, LLC @ https://www.philterd.ai
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
package ai.philterd.phileas.metrics;

import ai.philterd.phileas.model.enums.FilterType;
import ai.philterd.phileas.model.services.MetricsService;
import ai.philterd.philter.PhilterConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsync;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchAsyncClientBuilder;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.cloudwatch.CloudWatchConfig;
import io.micrometer.cloudwatch.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.datadog.DatadogConfig;
import io.micrometer.datadog.DatadogMeterRegistry;
import io.micrometer.jmx.JmxConfig;
import io.micrometer.jmx.JmxMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PhilterMetricsService implements MetricsService {

    private static final Logger LOGGER = LogManager.getLogger(PhilterMetricsService.class);

    private static final String TOTAL_DOCUMENTS_PROCESSED = "total.documents.processed";
    private static final String DOCUMENTS_PROCESSED = "documents.processed";

    private final transient Counter processed;
    private final transient Counter documents;
    private final transient Map<FilterType, Counter> filterTypes;
    private final transient Map<FilterType, Timer> filterTimers;

    public PhilterMetricsService(final PhilterConfiguration philterConfiguration) {

        final CompositeMeterRegistry compositeMeterRegistry = new CompositeMeterRegistry();

        compositeMeterRegistry.config().commonTags("application", "philter");

        if(StringUtils.isNotEmpty(philterConfiguration.metricsHostname())) {
            compositeMeterRegistry.config().commonTags("hostname", philterConfiguration.metricsHostname());
        }

        final int step = philterConfiguration.metricsStep();

        if(philterConfiguration.metricsJmxEnabled()) {

            LOGGER.info("Initializing JMX metric reporting.");

            final JmxConfig jmxConfig = new JmxConfig() {
                @Override
                public String get(String s) {
                    return null;
                }

                @Override
                public Duration step() {
                    return Duration.ofSeconds(step);
                }

                @Override
                public String prefix() {
                    return philterConfiguration.metricsPrefix();
                }
            };

            compositeMeterRegistry.add(new JmxMeterRegistry(jmxConfig, Clock.SYSTEM));

        }

        if(philterConfiguration.metricsPrometheusEnabled()) {
            
            LOGGER.info("Initializing Prometheus metric reporting.");

            final PrometheusConfig prometheusConfig = new PrometheusConfig() {

                @Override
                public Duration step() {
                    return Duration.ofSeconds(step);
                }

                @Override
                public String get(String k) {
                    return null;
                }

                @Override
                public String prefix() {
                    return philterConfiguration.metricsPrefix();
                }

            };

            final PrometheusMeterRegistry prometheusRegistry = new PrometheusMeterRegistry(prometheusConfig);
            final int port = philterConfiguration.metricsPrometheusPort();
            final String context = philterConfiguration.metricsPrometheusContext();

            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
                server.createContext("/" + context, httpExchange -> {
                    final String response = prometheusRegistry.scrape();
                    httpExchange.sendResponseHeaders(200, response.getBytes().length);
                    try (OutputStream os = httpExchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

                new Thread(server::start).start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            compositeMeterRegistry.add(prometheusRegistry);

        }

        if(philterConfiguration.metricsDataDogEnabled()) {

            LOGGER.info("Initializing Datadog metric reporting.");

            final String datadogApiKey = philterConfiguration.metricsDataDogApiKey();

            if (StringUtils.isEmpty(datadogApiKey)) {

                LOGGER.warn("Datadog metric reporting enabled but no Datadog API key provided. Reporting will not be enabled.");

            } else {

                final DatadogConfig datadogConfig = new DatadogConfig() {
                    @Override
                    public String apiKey() {
                        return philterConfiguration.metricsDataDogApiKey();
                    }

                    @Override
                    public Duration step() {
                        return Duration.ofSeconds(step);
                    }

                    @Override
                    public String get(String k) {
                        return null;
                    }

                    @Override
                    public String prefix() {
                        return philterConfiguration.metricsPrefix();
                    }

                };

                compositeMeterRegistry.add(new DatadogMeterRegistry(datadogConfig, Clock.SYSTEM));

            }

        }

        if(philterConfiguration.metricsCloudWatchEnabled()) {

            LOGGER.info("Initializing AWS CloudWatch metric reporting.");

            final CloudWatchConfig cloudWatchConfig = new CloudWatchConfig() {

                @Override
                public String get(String s) {
                    return null;
                }

                @Override
                public Duration step() {
                    return Duration.ofSeconds(step);
                }

                @Override
                public String namespace() {
                    return philterConfiguration.metricsCloudWatchNamespace();
                }

                @Override
                public String prefix() {
                    return philterConfiguration.metricsPrefix();
                }

                @Override
                public int batchSize() {
                    // 20 is the maximum batch size.
                    return CloudWatchConfig.MAX_BATCH_SIZE;
                }

            };

            final AmazonCloudWatchAsync amazonCloudWatchAsync = AmazonCloudWatchAsyncClientBuilder
                    .standard()
                    .withRegion(Regions.fromName(philterConfiguration.metricsCloudWatchRegion()))
                    .build();

            compositeMeterRegistry.add(new CloudWatchMeterRegistry(cloudWatchConfig, Clock.SYSTEM, amazonCloudWatchAsync));

        }

        this.processed = compositeMeterRegistry.counter(philterConfiguration.metricsPrefix() + "." + TOTAL_DOCUMENTS_PROCESSED);
        this.documents = compositeMeterRegistry.counter(philterConfiguration.metricsPrefix() + "." + DOCUMENTS_PROCESSED);

        // Add a counter for each filter type.
        this.filterTypes = new HashMap<>();
        for(final FilterType filterType : FilterType.values()) {
            filterTypes.put(filterType, compositeMeterRegistry.counter(philterConfiguration.metricsPrefix() + "." + filterType.name().toLowerCase().replace("-", ".")));
        }

        // Add a timer for each filter type.
        this.filterTimers = new HashMap<>();
        for(final FilterType filterType : FilterType.values()) {
            final String name = philterConfiguration.metricsPrefix() + "." + filterType.name().toLowerCase().replace("-", ".") + ".time.ms";
            filterTimers.put(filterType, compositeMeterRegistry.timer(name));
        }

    }

    @Override
    public void incrementFilterType(FilterType filterType) {

        filterTypes.get(filterType).increment();

    }

    @Override
    public void incrementProcessed() {

        incrementProcessed(1);

    }

    @Override
    public void incrementProcessed(long count) {

        processed.increment(count);
        documents.increment(count);

    }

    @Override
    public void logFilterTime(FilterType filterType, long timeMs) {

        filterTimers.get(filterType).record(timeMs, TimeUnit.MILLISECONDS);

    }

}
