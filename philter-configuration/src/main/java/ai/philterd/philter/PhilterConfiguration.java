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

    public PhilterConfiguration(Properties properties, String applicationName) throws IOException {
        this.properties = properties;
        this.applicationName = applicationName;
    }

    // Redaction Hub

    public boolean redactionHubEnabled() {
        return Boolean.parseBoolean(getProperty("redaction.hub.enabled", "false"));
    }

    public String redactionHubApiKey() {
        return getProperty("redaction.hub.api.key", "");
    }

    public String redactionHubBaseUrl() {
        return getProperty("redaction.hub.base.url", "");
    }

    public int redactionHubTimeOut() {
        return Integer.parseInt(getProperty("redaction.hub.timeout", "3000"));
    }

    public boolean redactionHubIgnoreSsl() {
        return Boolean.parseBoolean(getProperty("redaction.hub.ignore.ssl", "false"));
    }

    public String redactionHubCertificateName() {
        return getProperty("redaction.hub.certificate.name", "");
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
        return String.valueOf(getProperty("metrics.cloudwatch.region", "us-east-1"));
    }

    public String metricsCloudWatchNamespace() {
        return String.valueOf(getProperty("metrics.cloudwatch.namespace", applicationName));
    }

    public String metricsHostname() {
        return String.valueOf(getProperty("metrics.hostname", ""));
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