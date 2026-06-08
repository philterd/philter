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
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.SeparatedTermLists;
import ai.philterd.philter.security.ChaChaRandom;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.context.MongoContextService;
import ai.philterd.philter.services.context.NoOpContextService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.policies.PolicyResolver;
import ai.philterd.philter.services.vectors.MongoVectorService;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import ai.philterd.philter.utils.FilterTypeCounter;
import io.micrometer.core.instrument.MeterRegistry;
import ai.philterd.philter.utils.HttpUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mongodb.client.MongoClient;
import org.apache.commons.codec.digest.DigestUtils;
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
    private final RedactListsDataService redactListsService;
    private final ContextDataService contextService;
    private final AuditEventPublisher auditEventPublisher;
    private final LedgerDataService ledgerService;
    private final UserService userService;
    private final MeterRegistry meterRegistry;
    private final PhieldPublisher phieldPublisher;
    private final PiiCountAggregatePublisher piiCountAggregatePublisher;
    private final RedactionCache redactionCache;

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
                            final RedactListsDataService redactListsService,
                            final ContextDataService contextService,
                            final AuditEventPublisher auditEventPublisher,
                            final LedgerDataService ledgerService,
                            final UserService userService,
                            final MeterRegistry meterRegistry,
                            final PhieldPublisher phieldPublisher,
                            final PiiCountAggregatePublisher piiCountAggregatePublisher,
                            final RedactionCache redactionCache) {

        this.mongoClient = mongoClient;;
        this.policyDataService = policyDataService;
        this.customListService = customListService;
        this.redactListsService = redactListsService;
        this.contextService = contextService;
        this.auditEventPublisher = auditEventPublisher;
        this.ledgerService = ledgerService;
        this.userService = userService;
        this.meterRegistry = meterRegistry;
        this.phieldPublisher = phieldPublisher;
        this.piiCountAggregatePublisher = piiCountAggregatePublisher;
        this.redactionCache = redactionCache;

    }

    public AbstractFilterResult filter(final String policyName, final ObjectId userId, final String contextName, final byte[] body, final MimeType mimeType) throws Exception {

        final UserEntity userEntity = userService.findOneById(userId);

        // The user may have been deleted after the API key was cached.
        if (userEntity == null) {
            throw new Exception("The user associated with this request no longer exists.");
        }

        // The stored policy JSON, from the in-process cache when warm, else from the database. The JSON
        // can contain PII in filter-strategy conditions, which is why the cache is in-process only.
        String policyJson = redactionCache.getPolicyJson(userEntity.getId(), policyName);
        if (policyJson == null) {
            final PolicyEntity policyEntity = policyDataService.findOne(policyName, userEntity.getId());
            // The named policy must exist for this user.
            if (policyEntity == null) {
                throw new Exception("The policy '" + policyName + "' does not exist.");
            }
            policyJson = policyEntity.getPolicy();
            redactionCache.putPolicyJson(userEntity.getId(), policyName, policyJson);
        }

        // Resolve the user's stable FPE key (generating one if absent) and derive its tweak. FF3-1
        // requires a hex key and hex tweak; a stable key+tweak makes format-preserving encryption
        // deterministic for the user so the FPE_ENCRYPT_REPLACE strategy preserves referential integrity.
        // The key is injected into the policy as a fallback when the policy supplies no fpe object. It is
        // resolved fresh per request and never cached, so no key material lives in the cache.
        final String fpeKey = userService.ensureFpeKey(userEntity);
        final String fpeTweak = EncryptionService.deriveFpeTweak(fpeKey);

        // Deserialize the stored native Phileas policy and apply Philter-specific resolution
        // (custom list references and the managed FPE key fallback).
        final Policy phileasPolicy = new PolicyResolver(gson, customListService)
                .resolve(policyJson, userEntity.getId(), fpeKey, fpeTweak);

        // Get the always/never redact lists, from the in-process cache when warm, else the database.
        RedactionCache.CachedRedactLists cachedRedactLists = redactionCache.getRedactLists(userEntity.getId());
        if (cachedRedactLists == null) {
            final RedactListsEntity entity = redactListsService.find(userEntity.getId());
            final List<String> alwaysRedact = entity != null ? entity.getTermsToAlwaysRedact() : List.of();
            final List<String> neverRedact = entity != null ? entity.getTermsToNeverRedact() : List.of();
            redactionCache.putRedactLists(userEntity.getId(), alwaysRedact, neverRedact);
            cachedRedactLists = new RedactionCache.CachedRedactLists(alwaysRedact, neverRedact);
        }

        // Reconstruct the entity shape the rest of this method expects (never null; lists may be empty).
        final RedactListsEntity redactListsEntity = new RedactListsEntity();
        redactListsEntity.setTermsToAlwaysRedact(cachedRedactLists.getAlwaysRedact());
        redactListsEntity.setTermsToNeverRedact(cachedRedactLists.getNeverRedact());

        // Add in the never-redact (ignore) list.
        if(redactListsEntity != null && !redactListsEntity.getTermsToNeverRedact().isEmpty()) {
            final Ignored neverRedactIgnored = new Ignored("never-redact-list", redactListsEntity.getTermsToNeverRedact(), Collections.emptyList(), false);
            phileasPolicy.getIgnored().add(neverRedactIgnored);
        }

        // Add in the always-redact list.
        if(redactListsEntity != null && !redactListsEntity.getTermsToAlwaysRedact().isEmpty()) {

            // Break the terms into exact and fuzzy lists.
            final SeparatedTermLists separatedTermLists = redactListsEntity.breakAlwaysRedactIntoSeparateLists();

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

        // A context is optional. When the request supplies no context name (null or blank) the request
        // uses no context features: token replacements are neither persisted nor shared across
        // documents/requests, and any entity-type disambiguation is limited to the current document.
        final boolean useContext = contextName != null && !contextName.isBlank();

        // The context service either uses Mongo or is a NoOp (when no context is used).
        final ai.philterd.phileas.services.context.ContextService phileasContextService;

        // The vector service for entity type disambiguation.
        final VectorService vectorService;

        // Whether the Phileas span-disambiguation engine is enabled for this request.
        final boolean disambiguationEnabled;

        // Whether the redaction ledger is written for this request (a per-context feature).
        final boolean ledgerEnabled;

        if (!useContext) {

            // No context: do not touch the context store. Disambiguation, if it occurs, is scoped to
            // this single document via in-memory vectors that are discarded when the request completes.
            // The ledger is a context feature and is therefore off.
            LOGGER.info("No context specified; using the NoOp context service with document-scoped disambiguation.");
            phileasContextService = new NoOpContextService();
            vectorService = new InMemoryVectorService();
            disambiguationEnabled = true;
            ledgerEnabled = false;

        } else {

            // Find the context by its name.
            final ContextEntity contextEntity = contextService.findOneByNameAndUserId(contextName, userEntity.getId());

            if(contextEntity == null) {

                final String eventId = UUID.randomUUID().toString();
                final String errorMessage = "The context specified for this document no longer exists. It may have been deleted after the document was submitted for redaction. Please resubmit the document with a valid context or without a context. (Event ID: " + eventId + ")";

                throw new Exception(errorMessage);

            }

            // Create the MongoDB context service.
            LOGGER.info("Using MongoDB context service...");
            phileasContextService = new MongoContextService(mongoClient, contextCache, userEntity.getId(), contextEntity.getContextName(), auditEventPublisher);

            disambiguationEnabled = contextEntity.isDisambiguation();
            ledgerEnabled = contextEntity.isLedger();

            if (contextEntity.isDisambiguation()) {

                // Entity type disambiguation for this context is enabled.

                if (contextEntity.getDisambiguationScope().equalsIgnoreCase("Document")) {
                    vectorService = new InMemoryVectorService();
                } else if (contextEntity.getDisambiguationScope().equalsIgnoreCase("Context")) {
                    vectorService = new MongoVectorService(mongoClient, userEntity.getId(), auditEventPublisher);
                } else {
                    LOGGER.info("Invalid disambiguation scope: {}", contextEntity.getDisambiguationScope());
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

        // Enable the Phileas span-disambiguation engine when entity-type disambiguation is in effect:
        // for a context, when its disambiguation flag is on; for a no-context request, document-scoped
        // disambiguation is used. This is the single switch for the feature — without it the engine gate
        // defaults to off and the setting would silently have no effect.
        properties.put("span.disambiguation.enabled", Boolean.toString(disambiguationEnabled));

        final PhileasConfiguration phileasConfiguration = new PhileasConfiguration(properties);

        final PlainTextFilterService plainTextFilterService = new PlainTextFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);

        LOGGER.info("Processing text with Phileas");
        final AbstractFilterResult filterResult;

        // Pass a non-null context to Phileas; a no-context request uses an empty context name.
        final String effectiveContextName = useContext ? contextName : "";

        if(mimeType == MimeType.TEXT_PLAIN) {

            final String plainText = new String(body);
            filterResult = plainTextFilterService.filter(phileasPolicy, effectiveContextName, plainText);

        } else if(mimeType == MimeType.APPLICATION_PDF) {

            final PdfFilterService pdfFilterService = new PdfFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);
            filterResult = pdfFilterService.filter(phileasPolicy, effectiveContextName, body, MimeType.APPLICATION_PDF);

        } else if(mimeType == MimeType.IMAGE_JPEG) {

            final PdfFilterService pdfFilterService = new PdfFilterService(phileasConfiguration, phileasContextService, vectorService, random, httpClient);
            filterResult = pdfFilterService.filter(phileasPolicy, effectiveContextName, body, MimeType.IMAGE_JPEG);

        } else {

            throw new Exception("Unsupported MIME type: " + mimeType);

        }

        // Store the ledger from the redaction, but only when the request's context has the
        // redaction ledger enabled. The flag is per context and defaults to off.
        if (ledgerEnabled) {

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

        // Optionally publish the per-type counts (counts only, never any PII) to a Phield drift
        // monitor. No-op unless enabled in the admin settings; fire-and-forget so it cannot affect redaction.
        phieldPublisher.publish(filterResult.getContext(), filterTypeCounts);

        // Optionally record document-presence PII type counts for differential-privacy reporting with
        // Philter Diffuse. No-op unless enabled by the admin; best-effort so it cannot affect redaction.
        piiCountAggregatePublisher.record(filterResult.getContext(), filterTypeCounts.keySet());

    }

}
