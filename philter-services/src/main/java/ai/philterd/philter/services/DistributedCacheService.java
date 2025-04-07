package ai.philterd.philter.services;

import ai.philterd.phileas.model.enums.FilterType;
import ai.philterd.phileas.model.objects.Alert;
import ai.philterd.phileas.model.objects.Span;
import ai.philterd.phileas.model.services.CacheService;
import ai.philterd.philter.PhilterConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SslProvider;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class DistributedCacheService implements CacheService {

    private static final Logger LOGGER = LogManager.getLogger(DistributedCacheService.class);

    protected final RedissonClient redisson;

    public DistributedCacheService(final PhilterConfiguration philterConfiguration) throws IOException {

        final boolean cluster = philterConfiguration.cacheEnabled();
        final String redisEndpoint = philterConfiguration.cacheEndpoint();
        final int redisPort = philterConfiguration.cachePort();
        final String authToken = philterConfiguration.cacheAuthtoken();
        final boolean ssl = philterConfiguration.cacheSsl();

        final Config config = new Config();

        if (cluster) {

            final String protocol;

            if (ssl) {
                protocol = "rediss://";
            } else {
                protocol = "redis://";
            }

            final String redisAddress = protocol + redisEndpoint + ":" + redisPort;
            LOGGER.info("Using clustered redis connection: {}", redisAddress);

            config.useClusterServers()
                    .setScanInterval(2000)
                    .addNodeAddress(redisAddress)
                    .setPassword(authToken);

            if(ssl) {
                config.useClusterServers().setSslKeystore(new URL(philterConfiguration.cacheSslKeyStore()));
                config.useClusterServers().setSslKeystorePassword(philterConfiguration.cacheSslKeyStorePassword());
                config.useClusterServers().setSslTruststore(new URL(philterConfiguration.cacheTrustStore()));
                config.useClusterServers().setSslTruststorePassword(philterConfiguration.cacheTrustStorePassword());
                config.useClusterServers().setSslProvider(SslProvider.JDK);
            }

        } else {

            final String protocol;

            if (ssl) {
                protocol = "rediss://";
            } else {
                protocol = "redis://";
            }

            final String redisAddress = protocol + redisEndpoint + ":" + redisPort;
            LOGGER.info("Using single server redis connection {}", redisAddress);
            config.useSingleServer().setAddress(redisAddress);

            if(StringUtils.isNotEmpty(authToken)) {
                config.useSingleServer().setAddress(redisAddress).setPassword(authToken);
            } else {
                config.useSingleServer().setAddress(redisAddress);
            }

            if(ssl) {
                config.useSingleServer().setSslKeystore(new URL(philterConfiguration.cacheSslKeyStore()));
                config.useSingleServer().setSslKeystorePassword(philterConfiguration.cacheSslKeyStorePassword());
                config.useSingleServer().setSslTruststore(new URL(philterConfiguration.cacheTrustStore()));
                config.useSingleServer().setSslTruststorePassword(philterConfiguration.cacheTrustStorePassword());
                config.useSingleServer().setSslProvider(SslProvider.JDK);
            }

        }

        redisson = Redisson.create(config);

    }

    // For alerts

    @Override
    public void generateAlert(String policy, String strategyId, String context, String documentId, FilterType filterType) {
        alerts.add(new Alert(policy, strategyId, context, documentId, filterType.getType()));
    }

    @Override
    public List<Alert> getAlerts() {
        return alerts;
    }

    @Override
    public void deleteAlert(String alertId) {
        alerts.removeIf(alert -> StringUtils.equalsIgnoreCase(alert.getId(), alertId));
    }

    @Override
    public void clearAlerts() {
        alerts.clear();
    }

    // For anonymization

    @Override
    public String generateAnonymizationCacheKey(String context, String token) {
        return DigestUtils.md5Hex(context + "|" + token);
    }

    @Override
    public void putAnonymizedToken(String context, String token, String replacement) {
        anonymizationCache.put(generateAnonymizationCacheKey(context, token), replacement);
    }

    @Override
    public String getAnonymizedToken(String context, String token) {
        return anonymizationCache.get(generateAnonymizationCacheKey(context, token));
    }

    @Override
    public void removeAnonymizedToken(String context, String token) {
        anonymizationCache.remove(generateAnonymizationCacheKey(context, token));
    }

    @Override
    public boolean containsAnonymizedToken(String context, String token) {
        return anonymizationCache.containsKey(generateAnonymizationCacheKey(context, token));
    }

    @Override
    public boolean containsAnonymizedTokenValue(String context, String replacement) {
        return anonymizationCache.containsValue(replacement);
    }

    // For policies

    @Override
    public List<String> getPolicies() {
        return new ArrayList<>(policyCache.keySet());
    }

    @Override
    public String getPolicy(String policyName) throws IOException {
        return policyCache.get(policyName);
    }

    @Override
    public Map<String, String> getAllPolicies() {
        return policyCache;
    }

    @Override
    public void insertPolicy(String policyName, String policy) {
        policyCache.put(policyName, policy);
    }

    @Override
    public void removePolicy(String policyName) {
        policyCache.remove(policyName);
    }

    @Override
    public void clearPolicyCache() {
        policyCache.clear();
    }

    // For disambiguation

    @Override
    public void hashAndInsert(String context, double[] hashes, Span span, int vectorSize) {

        // Insert a new map for this context if it's needed to avoid an NPE.
        initializeVectorCache(context);

        for(double i = 0; i < hashes.length; i++) {

            if(hashes[(int) i] != 0) {

                if (vectorCache.get(context).get(span.getFilterType()).getVectorIndexes().get(i) == null) {
                    vectorCache.get(context).get(span.getFilterType()).getVectorIndexes().putIfAbsent(i, 0.0);
                }

                final double value = vectorCache.get(context).get(span.getFilterType()).getVectorIndexes().get(i);
                vectorCache.get(context).get(span.getFilterType()).getVectorIndexes().put(i, value + 1.0);

            }

        }

    }

    @Override
    public Map<Double, Double> getVectorRepresentation(String context, FilterType filterType) {

        // Insert a new map for this context if it's needed to avoid an NPE.
        initializeVectorCache(context);

        return vectorCache.get(context).get(filterType).getVectorIndexes();

    }

}