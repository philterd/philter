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
package ai.philterd.philter;

import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
import org.apache.commons.lang3.StringUtils;

public class PhilterConfiguration {

   private final Properties properties;
   private final String applicationName;

    public PhilterConfiguration(String propertyFileName, String applicationName) throws IOException {
        FileReader fileReader = new FileReader(propertyFileName);
        this.properties = new Properties();
        this.properties.load(fileReader);
        this.applicationName = applicationName;
    }

    public PhilterConfiguration(final Properties properties, final String applicationName) {
        this.properties = properties;
        this.applicationName = applicationName;
    }

    // Cache

    public boolean cacheEnabled() {
        return Boolean.parseBoolean(getProperty("cache.enabled", "false"));
    }

    public boolean cacheCluster() {
        return Boolean.parseBoolean(getProperty("cache.cluster", "false"));
    }

    public String cacheEndpoint() {
        return getProperty("cache.host", "127.0.0.1");
    }

    public int cachePort() {
        return Integer.parseInt(getProperty("cache.port", "6379"));
    }

    public String cacheAuthToken() {
        return getProperty("cache.auth.token", "");
    }

    public boolean cacheSsl() {
        return Boolean.parseBoolean(getProperty("cache.ssl", "false"));
    }

    public String cacheSslKeyStore() {
        return getProperty("cache.ssl.keystore", "");
    }

    public String cacheSslKeyStorePassword() {
        return getProperty("cache.ssl.keystore.password", "");
    }

    public String cacheTrustStore() {
        return getProperty("cache.ssl.truststore", "");
    }

    public String cacheTrustStorePassword() {
        return getProperty("cache.ssl.truststore.password", "");
    }

    // Metrics

    public String metricsPrefix() {
        return getProperty("metrics.prefix", applicationName);
    }

    // See: https://github.com/micrometer-metrics/micrometer/blob/master/implementations/micrometer-registry-prometheus/src/main/java/io/micrometer/prometheus/PrometheusConfig.java
    // The step size to use in computing windowed statistics like max. The default is 1 minute.
    // To get the most out of these statistics, align the step interval to be close to your scrape interval.
    public int metricsStep() {
        return Integer.parseInt(getProperty("metrics.step", "60"));
    }

    public boolean metricsJmxEnabled() {
        return Boolean.parseBoolean(getProperty("metrics.jmx.enabled", "false"));
    }

    public boolean metricsPrometheusEnabled() {
        return Boolean.parseBoolean(getProperty("metrics.prometheus.enabled", "false"));
    }

    public int metricsPrometheusPort() {
        return Integer.parseInt(getProperty("metrics.prometheus.port", "9100"));
    }

    public String metricsPrometheusContext() {
        return getProperty("metrics.prometheus.context", "metrics");
    }

    public boolean metricsDataDogEnabled() {
        return Boolean.parseBoolean(getProperty("metrics.datadog.enabled", "false"));
    }

    public String metricsDataDogApiKey() {
        return getProperty("metrics.datadog.apikey", "metrics");
    }

    public boolean metricsCloudWatchEnabled() {
        return Boolean.parseBoolean(getProperty("metrics.cloudwatch.enabled", "false"));
    }

    public String metricsCloudWatchRegion() {
        return getProperty("metrics.cloudwatch.region", "us-east-1");
    }

    public String metricsCloudWatchNamespace() {
        return getProperty("metrics.cloudwatch.namespace", applicationName);
    }

    public String metricsHostname() {
        return getProperty("metrics.hostname", "");
    }

    // Policy Services

    public String policiesDirectory() {
        return getProperty("filter.policies.directory", "./policies/");
    }

    public String policyService() {
        return getProperty("filter.policies.service", "local");
    }

    public String opensearchScheme() {
        return getProperty("filter.policies.service.opensearch.scheme", "http");
    }

    public String opensearchHost() {
        return getProperty("filter.policies.service.opensearch.host", "localhost");
    }

    public int opensearchPort() {
        return Integer.parseInt(getProperty("filter.policies.service.opensearch.port", "9200"));
    }

    private String getProperty(final String property, final String defaultValue) {

        final String environmentVariableValue = getEnvironmentVariable(property);

        if(!StringUtils.isEmpty(environmentVariableValue)) {
            return environmentVariableValue;
        }

        final String systemPropertyValue = getSystemProperty(property);

        if(!StringUtils.isEmpty(systemPropertyValue)) {
            return systemPropertyValue;
        }

        final String propertyFileValue = getFileProperty(property);

        if(!StringUtils.isEmpty(propertyFileValue)) {
            return propertyFileValue;
        }

        return defaultValue;

    }

    private String getEnvironmentVariable(final String environmentVariable) {
        return System.getenv(environmentVariable);
    }

    private String getSystemProperty(final String property) {
        return System.getProperty(property);
    }

    private String getFileProperty(final String property) {
        return properties.getProperty(property);
    }

}