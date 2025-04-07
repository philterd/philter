package ai.philterd.philter.services;

import ai.philterd.phileas.model.enums.FilterType;
import ai.philterd.phileas.model.objects.Alert;
import ai.philterd.phileas.model.objects.Span;
import ai.philterd.phileas.model.objects.SpanVector;
import ai.philterd.phileas.model.services.CacheService;
import ai.philterd.philter.PhilterConfiguration;
import com.google.gson.Gson;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.redisson.Redisson;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SslProvider;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DistributedCacheService implements CacheService {

    private static final Logger LOGGER = LogManager.getLogger(DistributedCacheService.class);

    protected final RedissonClient redisson;

    private static final String CACHE_LIST_NAME = "alert";
    private static final String CACHE_ENTRY_NAME = "anonymization";
    private static final String POLICIES_CACHE_KEY = "policies";

    private final Gson gson = new Gson();

    public DistributedCacheService(final PhilterConfiguration philterConfiguration) throws IOException {

        final boolean cluster = philterConfiguration.cacheEnabled();
        final String redisEndpoint = philterConfiguration.cacheEndpoint();
        final int redisPort = philterConfiguration.cachePort();
        final String authToken = philterConfiguration.cacheAuthToken();
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
        final Alert alert = new Alert(policy, strategyId, context, documentId, filterType.getType());
        redisson.getList(CACHE_LIST_NAME).add(alert);
    }

    @Override
    public List<Alert> getAlerts() {
        return redisson.getList(CACHE_LIST_NAME);
    }

    @Override
    public void deleteAlert(String alertId) {

        LOGGER.info("Deleting alert {}", alertId);

        final RList<Alert> alerts = redisson.getList(CACHE_LIST_NAME);

        int index = -1;

        for(int x = 0; x < alerts.size(); x++) {

            if(StringUtils.equalsIgnoreCase(alertId, alerts.get(x).getId())) {
                index = x;
                break;
            }

        }

        if(index != -1) {
            alerts.remove(index);
        }
    }

    @Override
    public void clearAlerts() {
        redisson.getKeys().delete(CACHE_LIST_NAME);
    }

    // For anonymization

    @Override
    public String generateAnonymizationCacheKey(String context, String token) {
        return DigestUtils.md5Hex(context + "|" + token);
    }

    @Override
    public void putAnonymizedToken(String context, String token, String replacement) {

        final String key = generateAnonymizationCacheKey(context, token);
        redisson.getMap(CACHE_ENTRY_NAME).put(key, replacement);

    }

    @Override
    public String getAnonymizedToken(String context, String token) {

        final String key = generateAnonymizationCacheKey(context, token);
        final RMap<String, String> map = redisson.getMap(CACHE_ENTRY_NAME);

        return map.get(key);

    }

    @Override
    public void removeAnonymizedToken(String context, String token) {

        final String key = generateAnonymizationCacheKey(context, token);
        final RMap<String, String> map = redisson.getMap(CACHE_ENTRY_NAME);

        map.remove(key);

    }

    @Override
    public boolean containsAnonymizedToken(String context, String token) {

        final String key = generateAnonymizationCacheKey(context, token);
        final RMap<String, String> map = redisson.getMap(CACHE_ENTRY_NAME);

        return map.containsKey(key);

    }

    @Override
    public boolean containsAnonymizedTokenValue(String context, String replacement) {

        final RMap<String, String> map = redisson.getMap(CACHE_ENTRY_NAME);

        return map.containsValue(replacement);

    }

    // For policies

    @Override
    public List<String> getPolicies() {

        // Get the names from the cache and return them.
        final RMap<String, String> names = redisson.getMap(POLICIES_CACHE_KEY);
        return new ArrayList<>(names.keySet());

    }

    @Override
    public String getPolicy(String policyName) throws IOException {

        // Get the policy from the cache and return it.
        final RMap<String, String> map = redisson.getMap(POLICIES_CACHE_KEY);
        return map.get(policyName);

    }

    @Override
    public Map<String, String> getAllPolicies() {

        final long count = redisson.getKeys().countExists(POLICIES_CACHE_KEY);

        if(count != 0) {

            // Get the policies from the cache and return them.
            final RMap<String, String> map = redisson.getMap(POLICIES_CACHE_KEY);

            Map<String, String> m = new HashMap<>();
            for(String k : map.keySet()) {
                m.put(k, map.get(k));
            }

            return m;

        } else {

            return new HashMap<>();

        }

    }

    @Override
    public void insertPolicy(String policyName, String policy) {

        final RMap<String, String> map = redisson.getMap(POLICIES_CACHE_KEY);
        map.put(policyName, policy);

    }

    @Override
    public void removePolicy(String policyName) {

        final RMap<String, String> map = redisson.getMap(POLICIES_CACHE_KEY);
        map.remove(policyName);

    }

    @Override
    public void clearPolicyCache() {

        final RMap<String, String> map = redisson.getMap(POLICIES_CACHE_KEY);
        map.clear();

    }

    // For disambiguation

    @Override
    public void hashAndInsert(String context, double[] hashes, Span span, int vectorSize) {

        final RMap<String, String> vectors = redisson.getMap(context);

        for(final double hash : hashes) {

            // Insert a new map for this context if it's needed to avoid an NPE.
            initializeVectorCache(span.getFilterType(), context);

            final SpanVector sv = gson.fromJson(vectors.getOrDefault(span.getFilterType().name(), new SpanVector().toString()), SpanVector.class);

            final double val = sv.getVectorIndexes().getOrDefault(hash, 1.0);
            sv.getVectorIndexes().put(hash, val + 1.0);

            vectors.put(span.getFilterType().name(), gson.toJson(sv));

        }

    }

    @Override
    public Map<Double, Double> getVectorRepresentation(String context, FilterType filterType) {

        // Insert a new map for this context if it's needed to avoid an NPE.
        initializeVectorCache(filterType, context);

        final Map<String, String> m = redisson.getMap(context);

        final SpanVector sv = gson.fromJson(m.get(filterType.name()), SpanVector.class);

        return sv.getVectorIndexes();

    }

    private void initializeVectorCache(FilterType filterType, String context) {

        final RMap<String, String> vectors = redisson.getMap(context);
        vectors.putIfAbsent(filterType.name(), new SpanVector().toString());

    }

}