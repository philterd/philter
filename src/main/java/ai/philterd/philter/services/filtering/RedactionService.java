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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import ai.philterd.phileas.model.filtering.IncrementalRedaction;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.policy.Ignored;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.strategies.custom.CustomDictionaryFilterStrategy;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.GlobalTermsEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.GlobalTermsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.SeparatedTermLists;
import ai.philterd.philter.security.ChaChaRandom;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.context.MongoContextService;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import ai.philterd.philter.services.vectors.MongoVectorService;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import ai.philterd.philter.utils.FilterTypeCounter;
import io.micrometer.core.instrument.MeterRegistry;
import ai.philterd.philter.utils.HttpUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mongodb.client.MongoClient;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

public class RedactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedactionService.class);

    private static final String CACHE_HOSTNAME = System.getenv("CACHE_HOSTNAME");
    private static final String CACHE_PASSWORD = System.getenv("CACHE_PASSWORD");
    private static final boolean CACHE_SSL = Boolean.parseBoolean(System.getenv("CACHE_SSL"));
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final MongoClient mongoClient;
    private final PolicyDataService policyDataService;
    private final CustomListDataService customListService;
    private final GlobalTermsDataService globalTermsService;
    private final ContextDataService contextService;
    private final AuditEventPublisher auditEventPublisher;
    private final LedgerDataService ledgerService;
    private final UserService userService;
    private final MeterRegistry meterRegistry;

    // Initializing this as static for the same reasons.
    private static final PoolingHttpClientConnectionManager connectionManager = createConnectionManager();

    private static PoolingHttpClientConnectionManager createConnectionManager() {
        try {
            return HttpUtils.getTrustAllPoolingHttpClientConnectionManagerBuilder()
                    .setMaxConnTotal(10)
                    .setMaxConnPerRoute(10)
                    .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                    .build();
        } catch (Exception ex) {
            throw new RuntimeException("Unable to create connection manager: " + ex.getMessage(), ex);
        }
    }

    // Reused across invocations in the same execution environment
    private static final CloseableHttpClient httpClient =
            HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    // Prefer explicit timeouts for Lambda to avoid hanging sockets
//                    .setDefaultRequestConfig(rc -> rc
//                            .setConnectionRequestTimeout(Timeout.ofSeconds(2)) // pool wait
//                            .setConnectTimeout(Timeout.ofSeconds(2))           // TCP connect
//                            .setResponseTimeout(Timeout.ofSeconds(10))         // read timeout
//                    )
                    // Lambda-friendly: keep connections reusable, but don't hold forever
                    .evictIdleConnections(TimeValue.ofSeconds(30))
                    .evictExpiredConnections()
                    // Helps reduce overhead when reusing across invocations
                    .disableAutomaticRetries() // optional; consider enabling if idempotent
                    .build();

    public RedactionService(final MongoClient mongoClient,
                            final PolicyDataService policyDataService,
                            final CustomListDataService customListService,
                            final GlobalTermsDataService globalTermsService,
                            final ContextDataService contextService,
                            final AuditEventPublisher auditEventPublisher,
                            final LedgerDataService ledgerService,
                            final UserService userService,
                            final MeterRegistry meterRegistry) {

        this.mongoClient = mongoClient;;
        this.policyDataService = policyDataService;
        this.customListService = customListService;
        this.globalTermsService = globalTermsService;
        this.contextService = contextService;
        this.auditEventPublisher = auditEventPublisher;
        this.ledgerService = ledgerService;
        this.userService = userService;
        this.meterRegistry = meterRegistry;

    }

    public AbstractFilterResult filter(final String policyName, final ObjectId userId, final String contextName, final byte[] body, final MimeType mimeType) throws Exception {

        final UserEntity userEntity = userService.findOneById(userId);

        // The user may have been deleted after the API key was cached.
        if (userEntity == null) {
            throw new Exception("The user associated with this request no longer exists.");
        }

        final PolicyEntity policyEntity = policyDataService.findOne(policyName, userEntity.getId());

        // The named policy must exist for this user.
        if (policyEntity == null) {
            throw new Exception("The policy '" + policyName + "' does not exist.");
        }

        // Build the simplified policy.
        final SimplifiedPolicy simplifiedPolicy = gson.fromJson(policyEntity.getPolicy(), SimplifiedPolicy.class);

        // Generate an FPE tweak value.
        final String fpeTweak = RandomStringUtils.secure().nextAlphanumeric(16);

        // Convert the simplified policy to a Phileas policy.
        final Policy phileasPolicy = simplifiedPolicy.toPolicy(userEntity.getFpeKey(), fpeTweak, customListService, userEntity.getId());

        // Get the global terms to always/never redact.
        final GlobalTermsEntity globalTermsEntity = globalTermsService.find(userEntity.getId());

        // Add in the global terms to ignore.
        if(globalTermsEntity != null && !globalTermsEntity.getTermsToNeverRedact().isEmpty()) {
            final Ignored globalIgnored = new Ignored("global-ignore-terms", globalTermsEntity.getTermsToNeverRedact(), Collections.emptyList(), false);
            phileasPolicy.getIgnored().add(globalIgnored);
        }

        // Add in the global terms to always redact.
        if(globalTermsEntity != null && !globalTermsEntity.getTermsToAlwaysRedact().isEmpty()) {

            // Break the terms into exact and fuzzy lists.
            final SeparatedTermLists separatedTermLists = globalTermsEntity.breakAlwaysRedactIntoSeparateLists();

            // Add exact terms.
            final CustomDictionary exactCustomDictionary = new CustomDictionary();
            exactCustomDictionary.setTerms(separatedTermLists.getExact());
            exactCustomDictionary.setFuzzy(false);
            exactCustomDictionary.setCustomDictionaryFilterStrategies(List.of(new CustomDictionaryFilterStrategy()));

            // Add fuzzy terms.
            final CustomDictionary fuzzyCustomDictionary = new CustomDictionary();
            fuzzyCustomDictionary.setTerms(separatedTermLists.getFuzzy());
            fuzzyCustomDictionary.setFuzzy(true);
            fuzzyCustomDictionary.setCustomDictionaryFilterStrategies(List.of(new CustomDictionaryFilterStrategy()));

            if(phileasPolicy.getIdentifiers().getCustomDictionaries() != null) {
                phileasPolicy.getIdentifiers().getCustomDictionaries().add(exactCustomDictionary);
                phileasPolicy.getIdentifiers().getCustomDictionaries().add(fuzzyCustomDictionary);
            } else {
                phileasPolicy.getIdentifiers().setCustomDictionaries(List.of(exactCustomDictionary, fuzzyCustomDictionary));
            }

        }

        // Initialize the contextCache. It is created per request and must be closed before returning
        // so that, when backed by Valkey/Redis, its connection pool is released rather than leaked.
        LOGGER.info("Initializing contextCache with hostname: {}:6379", CACHE_HOSTNAME);
        final ContextCache contextCache = new ContextCache(CACHE_HOSTNAME, 6379, CACHE_PASSWORD, CACHE_SSL);

        try {

        // The context service will either use Mongo or be a NoOp (when a context is not used).
        final ai.philterd.phileas.services.context.ContextService phileasContextService;

        // Set up the vector service for entity type disambiguation.
        final VectorService vectorService;

        // Find the context by its name.
        final ContextEntity contextEntity = contextService.findOneByNameAndUserId(contextName, userEntity.getId());

        if(contextEntity == null) {

            final String eventId = UUID.randomUUID().toString();
            final String errorMessage = "The context specified for this document no longer exists. It may have been deleted after the document was submitted for redaction. Please resubmit the document with a valid context or without a context. (Event ID: " + eventId + ")";

            throw new Exception(errorMessage);

        } else {

            // Create the MongoDB context service.
            LOGGER.info("Using MongoDB context service...");
            phileasContextService = new MongoContextService(mongoClient, contextCache, userEntity.getId(), contextEntity.getContextName(), auditEventPublisher);

            if (contextEntity.isDisambiguation()) {

                // Entity type disambiguation for this context is enabled.

                if (simplifiedPolicy.getDisambiguationScope().equalsIgnoreCase("Document")) {
                    vectorService = new InMemoryVectorService();
                } else if (simplifiedPolicy.getDisambiguationScope().equalsIgnoreCase("Context")) {
                    vectorService = new MongoVectorService(mongoClient, userEntity.getId(), auditEventPublisher);
                } else {
                    LOGGER.info("Invalid disambiguation scope: {}", simplifiedPolicy.getDisambiguationScope());
                    vectorService = new InMemoryVectorService();
                }

            } else {

                // Entity type disambiguation for this context is disabled.
                vectorService = new NoOpVectorService();

            }

        }

        // Create a document ID.
        final String documentId = UUID.randomUUID().toString();

        final Random random = new ChaChaRandom();

        // Create the Phileas configuration based on the user's settings. Incremental redactions are
        // required for the ledger and default to enabled; the INCREMENTAL_REDACTIONS_ENABLED
        // environment variable allows overriding it.
        final Properties properties = new Properties();
        properties.put("incremental.redactions.enabled",
                System.getenv().getOrDefault("INCREMENTAL_REDACTIONS_ENABLED", "true"));

        // Enable the Phileas span-disambiguation engine when (and only when) the context has entity
        // type disambiguation turned on. This makes the context's disambiguation flag the single
        // switch for the feature: without this, the engine gate defaults to off and the flag would
        // silently have no effect.
        properties.put("span.disambiguation.enabled", Boolean.toString(contextEntity.isDisambiguation()));

        final PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);

        final PlainTextFilterService plainTextFilterService = new PlainTextFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);

        LOGGER.info("Processing text with Phileas");
        final AbstractFilterResult filterResult;

        if(mimeType == MimeType.TEXT_PLAIN) {

            final String plainText = new String(body);
            filterResult = plainTextFilterService.filter(phileasPolicy, contextName, plainText);

        } else if(mimeType == MimeType.APPLICATION_PDF) {

            final PdfFilterService pdfFilterService = new PdfFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);
            filterResult = pdfFilterService.filter(phileasPolicy, contextName, body, MimeType.APPLICATION_PDF);

        } else if(mimeType == MimeType.IMAGE_JPEG) {

            final PdfFilterService pdfFilterService = new PdfFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);
            filterResult = pdfFilterService.filter(phileasPolicy, contextName, body, MimeType.IMAGE_JPEG);

        } else {

            throw new Exception("Unsupported MIME type: " + mimeType);

        }

        // Store the ledger from the redaction, but only when the request's context has the
        // redaction ledger enabled. The flag is per context and defaults to off.
        if (contextEntity.isLedger()) {

            LOGGER.info("Initializing the ledger");
            ledgerService.initializeLedger(userEntity.getId(), documentId, DigestUtils.sha256Hex(body), "none-provided");

            LOGGER.info("Persisting ledger entries: " + filterResult.getIncrementalRedactions().size());
            for (final IncrementalRedaction incrementalRedaction : filterResult.getIncrementalRedactions()) {

                final LedgerEntity ledgerEntity = new LedgerEntity();
                ledgerEntity.setDocumentId(documentId);
                ledgerEntity.setReplacement(incrementalRedaction.getSpan().getReplacement());
                ledgerEntity.setToken(incrementalRedaction.getSpan().getText());
                ledgerEntity.setUserId(userEntity.getId());
                ledgerEntity.setDocumentHash(incrementalRedaction.getHash());
                ledgerEntity.setPreviousHash(ledgerService.getLatestTransaction(userEntity.getId(), documentId).getHash());
                ledgerEntity.setTimestamp(new Date());
                ledgerEntity.setFilename("none-provided");
                ledgerEntity.setType(incrementalRedaction.getSpan().getFilterType().getType());

                ledgerService.addTransaction(ledgerEntity);

            }

        } else {
            LOGGER.debug("Redaction ledger disabled for user; skipping ledger write.");
        }

        final long tokenCount = filterResult.getTokens();

        LOGGER.debug("Redaction request processed successfully. Tokens: {}, Redactions: {}", tokenCount, filterResult.getExplanation().appliedSpans().size());

        // Publish redaction metrics to Micrometer (scraped via /actuator/prometheus).
        recordRedactionMetrics(filterResult);

        // Audit that the document redaction completed. The generated documentId serves as the
        // request correlation id, and the number of redactions is recorded as a detail.
        auditEventPublisher.auditEvent(documentId, AuditLogEvent.DOCUMENT_REDACTION_COMPLETED,
                userEntity.getId(), null, null,
                "redactions: " + filterResult.getExplanation().appliedSpans().size());

        return filterResult;

        } finally {
            // Release the per-request cache (closes the Valkey/Redis pool when one is configured).
            contextCache.close();
        }

    }

    /**
     * Publishes redaction metrics for a completed filter result to Micrometer: a token counter and a
     * per-filter-type redaction counter. Exposed at package scope so the counter emission can be
     * tested with a real {@link MeterRegistry} without standing up the full filtering pipeline.
     */
    void recordRedactionMetrics(final AbstractFilterResult filterResult) {

        meterRegistry.counter("philter.tokens").increment(filterResult.getTokens());

        final Map<String, Integer> filterTypeCounts = FilterTypeCounter.countFilterTypes(filterResult.getExplanation().appliedSpans());

        for (final Map.Entry<String, Integer> filterTypeCount : filterTypeCounts.entrySet()) {
            meterRegistry.counter("philter.redactions", "filter_type", filterTypeCount.getKey()).increment(filterTypeCount.getValue());
        }

    }

}
