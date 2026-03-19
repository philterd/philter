package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.IncrementalRedaction;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.policy.Ignored;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.strategies.custom.CustomDictionaryFilterStrategy;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.GlobalTermsEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ChangeSetDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.GlobalTermsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.ChangeSetType;
import ai.philterd.philter.model.SeparatedTermLists;
import ai.philterd.philter.security.ChaChaRandom;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.context.MongoContextService;
import ai.philterd.philter.services.context.NoOpContextService;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import ai.philterd.philter.services.vectors.MongoVectorService;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import ai.philterd.philter.utils.FilterTypeCounter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

public class FilterService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FilterService.class);

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
    private final ChangeSetDataService changeSetService;
    private final LedgerDataService ledgerService;

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

    public FilterService(final MongoClient mongoClient, final PolicyDataService policyDataService,
                         final CustomListDataService customListService, final GlobalTermsDataService globalTermsService,
                         final ContextDataService contextService,
                         final ChangeSetDataService changeSetService,
                         final AuditEventPublisher auditEventPublisher,
                         final LedgerDataService ledgerService) {

        this.mongoClient = mongoClient;;
        this.policyDataService = policyDataService;
        this.customListService = customListService;
        this.globalTermsService = globalTermsService;
        this.contextService = contextService;
        this.auditEventPublisher = auditEventPublisher;
        this.changeSetService = changeSetService;
        this.ledgerService = ledgerService;

    }

    public void filter(final String policyName, final UserEntity userEntity, final String body, final ObjectId contextId) throws Exception {

        final PolicyEntity policyEntity = policyDataService.findOne(policyName, userEntity.getId());

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

        // Initialize the contextCache.
        LOGGER.info("Initializing contextCache with hostname: {}:6379", CACHE_HOSTNAME);
        final ContextCache contextCache = new ContextCache(CACHE_HOSTNAME, 6379, CACHE_PASSWORD, CACHE_SSL);

        // The context service will either use Mongo or be a NoOp (when a context is not used).
        final ai.philterd.phileas.services.context.ContextService phileasContextService;
        final String contextName;

        // Set up the vector service for entity type disambiguation.
        final VectorService vectorService;

        if(contextId != null) {

            // If there is a context in the request make sure it exists.

            final ContextEntity contextEntity = contextService.findOneByIdAndUserId(contextId, userEntity.getId());

            // Check if the context exists - it may have been deleted after document submission
            if(contextEntity == null) {

                final String eventId = UUID.randomUUID().toString();
                final String errorMessage = "The context specified for this document no longer exists. It may have been deleted after the document was submitted for redaction. Please resubmit the document with a valid context or without a context. (Event ID: " + eventId + ")";

               // TODO: Send webhook notification for failure

                throw new Exception("Context not found.");

            } else {

                contextName = contextEntity.getContextName();

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

        } else {

            // Not using a context for this redaction.
            LOGGER.info("Not using a context.");
            contextName = "none";
            phileasContextService = new NoOpContextService();

            // Entity type disambiguation for this context is disabled.
            vectorService = new NoOpVectorService();

        }

        // Create a document ID.
        final String documentId = UUID.randomUUID().toString();

        final Random random = new ChaChaRandom();

        // Create the Phileas configuration based on the user's settings.
        final Properties properties = new Properties();
        properties.put("incremental.redactions.enabled", "true");  // Required for ledger.
        final PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);

        final PlainTextFilterService plainTextFilterService = new PlainTextFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);

        LOGGER.info("Processing text with Phileas");
        final TextFilterResult filterResult = plainTextFilterService.filter(phileasPolicy, contextName, body);

        final Map<Integer, Explanation> explanations = new HashMap<>();
        explanations.put(0, filterResult.getExplanation());

        // Write the changeset.
        if(userEntity.isChangesetsEnabled()) {
            changeSetService.saveChangeSet(userEntity.getId(), documentId, explanations, ChangeSetType.NONE);
        }

        // Store the ledger from the redaction.
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

        final long tokenCount = filterResult.getTokens();

        LOGGER.debug("Redaction request processed successfully. Tokens: {}, Redactions: {}", tokenCount, filterResult.getExplanation().appliedSpans().size());

        // Publish the tokens/redactions for this document.
        final Map<String, Integer> filterTypeCounts = FilterTypeCounter.countFilterTypes(filterResult.getExplanation().appliedSpans());
        // TODO: Write these values to OpenSearch.

        // TODO: Audit that document redaction has been completed
        //auditEventPublisher.auditEvent(requestId, AuditLogEvent.DOCUMENT_REDACTION_COMPLETED, userEntity.getId(), redactedDocumentEntity.getId(), redactedDocumentEntity.getClientIp());

        // TODO: Send webhook notification for successful completion
        //sendWebhookNotification(userEntity, redactedDocumentEntity, WebhookEventType.DOCUMENT_REDACTION_COMPLETE, "");

    }

}
