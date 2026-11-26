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

import ai.philterd.philter.audit.AuditDetails;
import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import ai.philterd.phileas.model.filtering.IncrementalRedaction;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.policy.Ignored;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.policy.config.Email;
import ai.philterd.phileas.services.email.EncryptedEmailException;
import ai.philterd.phileas.services.filters.filtering.EmailFilterService;
import ai.philterd.phileas.services.filters.filtering.OoxmlFilterService;
import ai.philterd.phileas.services.filters.filtering.PdfFilterService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.ReviewSampler;
import ai.philterd.phileas.model.filtering.LeftoverCheckResult;
import ai.philterd.phileas.model.filtering.LeftoverFinding;
import ai.philterd.phileas.policy.config.LeftoverCheck;
import ai.philterd.phileas.services.filters.filtering.FilterOptions;
import ai.philterd.phileas.services.pdf.PdfPagePolicies;
import ai.philterd.phileas.services.strategies.custom.CustomDictionaryFilterStrategy;
import ai.philterd.philter.api.exceptions.BadRequestException;
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
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.SeparatedTermLists;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.context.ContextServices;
import ai.philterd.philter.services.context.NoOpContextService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.policies.PolicyResolver;
import ai.philterd.philter.services.policies.PolicyNotFoundException;
import ai.philterd.philter.services.vectors.VectorServices;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import ai.philterd.philter.utils.EnvUtils;
import ai.philterd.philter.utils.FilterTypeCounter;
import io.micrometer.core.instrument.MeterRegistry;
import ai.philterd.philter.utils.HttpUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RedactionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedactionService.class);

    /** Recorded in the ledger when the caller sent no filename. */
    static final String NO_FILENAME = "none-provided";

    private static final String CACHE_HOSTNAME = System.getenv("CACHE_HOSTNAME");
    static final int CACHE_PORT = EnvUtils.getInt("CACHE_PORT", 6379);
    private static final String CACHE_PASSWORD = System.getenv("CACHE_PASSWORD");
    private static final boolean CACHE_SSL = Boolean.parseBoolean(System.getenv("CACHE_SSL"));
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private final ContextServices contextServices;
    private final VectorServices vectorServices;
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

    // Built once per span-disambiguation variant and reused, rather than rebuilt every request.
    private final PhileasConfigurationCache phileasConfigurationCache = new PhileasConfigurationCache();

    // Warm filter services, built once per span-disambiguation variant and reused across requests so
    // their per-policy filter caches stay populated instead of being rebuilt every request. Each
    // request passes its own context and vector service to filter(...).
    private final ConcurrentMap<Boolean, PlainTextFilterService> plainTextFilterServices = new ConcurrentHashMap<>();
    private final ConcurrentMap<Boolean, PdfFilterService> pdfFilterServices = new ConcurrentHashMap<>();
    private final ConcurrentMap<Boolean, OoxmlFilterService> ooxmlFilterServices = new ConcurrentHashMap<>();
    private final ConcurrentMap<Boolean, EmailFilterService> emailFilterServices = new ConcurrentHashMap<>();

    // Initializing this as static for the same reasons.
    private static final PoolingHttpClientConnectionManager connectionManager = createConnectionManager();

    private static PoolingHttpClientConnectionManager createConnectionManager() {
        try {
            return HttpUtils.getPoolingHttpClientConnectionManagerBuilder()
                    .setMaxConnTotal(10)
                    .setMaxConnPerRoute(10)
                    // PhEye and generator calls set their own response timeouts; this bounds connecting.
                    .setDefaultConnectionConfig(ConnectionConfig.custom()
                            .setConnectTimeout(Timeout.ofSeconds(10))
                            .setValidateAfterInactivity(TimeValue.ofSeconds(5))
                            .build())
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
                    // A service a policy may reach could otherwise redirect the request somewhere it may not.
                    .disableRedirectHandling()
                    .build();

    public RedactionService(final ContextServices contextServices,
                            final VectorServices vectorServices,
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

        this.contextServices = contextServices;
        this.vectorServices = vectorServices;
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

    // Returns the warm PlainTextFilterService for the given span-disambiguation setting, building it on
    // first use. The context and vector services are supplied per call, so a single instance is reused
    // across requests with its per-policy filter cache populated.
    private PlainTextFilterService plainTextFilterService(final boolean disambiguationEnabled) {
        return plainTextFilterServices.computeIfAbsent(disambiguationEnabled,
                flag -> new PlainTextFilterService(phileasConfigurationCache.get(flag), httpClient));
    }

    // Returns the warm PdfFilterService for the given span-disambiguation setting, building it on first
    // use. As above, the context and vector services are supplied per call.
    /** A PDF redaction slot: waited for by the queue worker, refused with 503 to a request when none is free. */
    private static PdfRenderSlots.Slot pdfSlot(final boolean wait) throws InterruptedException {
        if (wait) {
            return PdfRenderSlots.acquire();
        }
        final PdfRenderSlots.Slot slot = PdfRenderSlots.tryAcquire();
        if (slot == null) {
            throw new ai.philterd.philter.data.services.QueueCapacityException("Philter is already redacting as many PDFs as "
                    + "it allows at once. Retry shortly, or submit the PDF asynchronously (async=true) to queue it.", 503);
        }
        return slot;
    }

    private PdfFilterService pdfFilterService(final boolean disambiguationEnabled) {
        return pdfFilterServices.computeIfAbsent(disambiguationEnabled,
                flag -> new PdfFilterService(phileasConfigurationCache.get(flag), httpClient));
    }

    private OoxmlFilterService ooxmlFilterService(final boolean disambiguationEnabled) {
        return ooxmlFilterServices.computeIfAbsent(disambiguationEnabled,
                flag -> new OoxmlFilterService(phileasConfigurationCache.get(flag), httpClient));
    }

    private EmailFilterService emailFilterService(final boolean disambiguationEnabled) {
        return emailFilterServices.computeIfAbsent(disambiguationEnabled,
                flag -> new EmailFilterService(phileasConfigurationCache.get(flag), httpClient));
    }

    public RedactionOutcome filter(final String policyName, final String userId, final String contextName, final byte[] body, final MimeType mimeType) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, (PinnedPolicy) null, null, null);
    }

    public RedactionOutcome filter(final String policyName, final String userId, final String contextName, final byte[] body, final MimeType mimeType, final String filename) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, (PinnedPolicy) null, filename, null);
    }

    /** As above, with the caller's {@link FilterOptions}, such as a request for the policy's leftover check. */
    public RedactionOutcome filter(final String policyName, final String userId, final String contextName, final byte[] body,
                                   final MimeType mimeType, final String filename, final FilterOptions options) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, null, filename, null, () -> {}, false, null, options);
    }

    /** A PDF whose page sets in {@code pagePolicies} are redacted with their own policies. */
    public RedactionOutcome filter(final String policyName, final String userId, final String contextName, final byte[] body,
                                   final MimeType mimeType, final String filename,
                                   final List<PagePolicyRequest> pagePolicies) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, filename, pagePolicies, FilterOptions.defaults());
    }

    /** As above, with the caller's {@link FilterOptions}. */
    public RedactionOutcome filter(final String policyName, final String userId, final String contextName, final byte[] body,
                                   final MimeType mimeType, final String filename,
                                   final List<PagePolicyRequest> pagePolicies, final FilterOptions options) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, null, filename, null, () -> {}, false, pagePolicies, options);
    }

    /**
     * Redacts the document and stamps the governing policy version onto the evidence. When
     * {@code pinnedPolicy} is non-null the redaction uses, and stamps, exactly that pinned version
     * (used by deferred/async redaction so the version in force at request time governs the job);
     * otherwise the user's current policy named {@code policyName} is resolved and used.
     *
     * <p>{@code requestedDocumentId} records the redaction under an id the caller already published;
     * null generates one. Either way it comes back on the {@link RedactionOutcome}.
     */
    public RedactionOutcome filter(final String policyName, final String userId, final String contextName, final byte[] body, final MimeType mimeType, final PinnedPolicy pinnedPolicy, final String filename, final String requestedDocumentId) throws Exception {
        // A request waiting on its response is refused when no PDF slot is free, rather than held.
        return filter(policyName, userId, contextName, body, mimeType, pinnedPolicy, filename, requestedDocumentId, () -> {}, false, null,
                FilterOptions.defaults());
    }

    /**
     * For the queue worker. The async publication fence runs after computation and before any completed-result
     * publication. A PDF waits for a free slot. Review selection was drawn when the document was accepted, so the
     * result's own selection is not recorded.
     */
    public RedactionOutcome filter(final String policyName, final String userId, final String contextName,
                                   final byte[] body, final MimeType mimeType, final PinnedPolicy pinnedPolicy,
                                   final String filename, final String requestedDocumentId,
                                   final Runnable beforePublication) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, pinnedPolicy, filename, requestedDocumentId,
                beforePublication, FilterOptions.defaults());
    }

    /** For the queue worker, with the options the request was accepted with. */
    public RedactionOutcome filter(final String policyName, final String userId, final String contextName,
                                   final byte[] body, final MimeType mimeType, final PinnedPolicy pinnedPolicy,
                                   final String filename, final String requestedDocumentId,
                                   final Runnable beforePublication, final FilterOptions options) throws Exception {
        return filter(policyName, userId, contextName, body, mimeType, pinnedPolicy, filename, requestedDocumentId,
                beforePublication, true, null, options);
    }

    private RedactionOutcome filter(final String policyName, final String userId, final String contextName,
                                    final byte[] body, final MimeType mimeType, final PinnedPolicy pinnedPolicy,
                                    final String filename, final String requestedDocumentId,
                                    final Runnable beforePublication, final boolean queued,
                                    final List<PagePolicyRequest> pagePolicyRequests,
                                    final FilterOptions options) throws Exception {

        final UserEntity userEntity = userService.findOneById(userId);

        // The user may have been deleted after the API key was cached.
        if (userEntity == null) {
            throw new Exception("The user associated with this request no longer exists.");
        }

        // Resolve the policy that governs this redaction: its JSON body and version. When a pinned
        // version is supplied (async), use it verbatim so the job is governed by the version in force
        // when the request was accepted. Otherwise resolve the current policy, from the in-process
        // cache when warm, else the database. The JSON can contain PII in filter-strategy conditions,
        // which is why the cache is in-process only.
        final String policyJson;
        final int policyRevision;
        if (pinnedPolicy != null) {
            policyJson = pinnedPolicy.policyJson();
            policyRevision = pinnedPolicy.version();
        } else {
            final RedactionCache.CachedPolicy stored = storedPolicy(policyName, userEntity.getId());
            policyJson = stored.getPolicyJson();
            policyRevision = stored.getRevision();
        }

        // The policy version stamped onto the evidence: name + revision + content fingerprint. The
        // content hash is derived from the JSON so it is always available, even on a cache hit.
        final String policyContentHash = pinnedPolicy != null
                ? pinnedPolicy.contentHash()
                : PolicyVersionDataService.contentHash(policyJson);
        final AppliedPolicy appliedPolicy = new AppliedPolicy(policyName, policyRevision, policyContentHash);

        final EffectiveConfiguration effective;
        if (pinnedPolicy != null && pinnedPolicy.effectiveJson() != null) {
            if (!java.util.Objects.equals(pinnedPolicy.effectiveHash(), PolicyVersionDataService.contentHash(pinnedPolicy.effectiveJson()))) {
                throw new IllegalStateException("Effective configuration fingerprint mismatch.");
            }
            effective = gson.fromJson(pinnedPolicy.effectiveJson(), EffectiveConfiguration.class);
        } else effective = null;
        final Policy phileasPolicy = effective == null ? resolveEffectivePolicy(policyJson, userEntity)
                : gson.fromJson(effective.policyJson(), Policy.class);

        // Refuse a policy Phileas will not load with its reason, before any context or ledger work, rather than
        // letting the refusal surface from filtering as a server error. A policy saved before Philter checked
        // this on save, or that a later Phileas release refuses, reaches here.
        final String refusal = PolicyLoadCheck.shared().refusal(phileasPolicy);
        if (refusal != null) {
            throw new ai.philterd.philter.services.policies.PolicyResolutionException(refusal);
        }

        refuseLeftoverCheckIfOff(phileasPolicy, options);

        final PagePolicies pagePolicies = effective != null
                ? pinnedPagePolicies(effective.pagePolicies())
                : resolvePagePolicies(pagePolicyRequests, userEntity);
        if (pagePolicies != null && mimeType != MimeType.APPLICATION_PDF) {
            throw new BadRequestException(PagePolicyRequest.PARAMETER + " applies only to PDF documents.");
        }

        // Initialize the contextCache. It is created per request and must be closed before returning
        // so that, when backed by Valkey/Redis, its connection pool is released rather than leaked.
        LOGGER.info("Initializing contextCache with hostname: {}:{}", CACHE_HOSTNAME, CACHE_PORT);
        final ContextCache contextCache = new ContextCache(CACHE_HOSTNAME, CACHE_PORT, CACHE_PASSWORD, CACHE_SSL);

        try {

        // A context is optional. When the request supplies no context name (null or blank) the request
        // uses no context features: token replacements are neither persisted nor shared across
        // documents/requests, and any entity-type disambiguation is limited to the current document.
        final boolean useContext = contextName != null && !contextName.isBlank();

        // The context service either uses the stored context or is a NoOp (when no context is used).
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
            final ContextEntity contextEntity = effective == null ? contextService.findOneByNameAndUserId(contextName, userEntity.getId())
                    : effective.contextJson() == null ? null : ContextSnapshots.fromJson(effective.contextJson());

            if(contextEntity == null) {

                // A request naming a context that does not exist is the caller's mistake, refused as asynchronous
                // submission refuses it. A queued document carries the context captured when it was accepted, so
                // the failure below is a safeguard for a job whose captured configuration has no context.
                if (pinnedPolicy == null) {
                    throw new ai.philterd.philter.services.policies.PolicyResolutionException("Context does not exist.");
                }

                final String eventId = UUID.randomUUID().toString();
                final String errorMessage = "The context specified for this document no longer exists. It may have been deleted after the document was submitted for redaction. Please resubmit the document with a valid context or without a context. (Event ID: " + eventId + ")";

                throw new ai.philterd.philter.services.policies.PolicyResolutionException(errorMessage);

            }

            LOGGER.info("Using the stored context service...");
            phileasContextService = contextServices.forContext(contextCache, userEntity.getId(), contextEntity.getContextName());

            disambiguationEnabled = contextEntity.isDisambiguation();
            ledgerEnabled = contextEntity.isLedger();

            if (contextEntity.isDisambiguation()) {

                // Entity type disambiguation for this context is enabled.

                if (contextEntity.getDisambiguationScope().equalsIgnoreCase("Document")) {
                    vectorService = new InMemoryVectorService();
                } else if (contextEntity.getDisambiguationScope().equalsIgnoreCase("Context")) {
                    vectorService = vectorServices.forUser(userEntity.getId());
                } else {
                    LOGGER.info("Invalid disambiguation scope: {}", contextEntity.getDisambiguationScope());
                    vectorService = new InMemoryVectorService();
                }

            } else {

                // Entity type disambiguation for this context is disabled.
                vectorService = new NoOpVectorService();

            }

        }

        final String documentId = requestedDocumentId != null ? requestedDocumentId : UUID.randomUUID().toString();

        // The disambiguation flag is the single switch for the span-disambiguation engine and the only
        // per-request config input, so the warm filter services (with their populated per-policy filter
        // caches) are kept per flag and reused. This request's context and vector services are passed
        // per call rather than baked into the service.
        final PlainTextFilterService plainTextFilterService = plainTextFilterService(disambiguationEnabled);

        LOGGER.info("Processing text with Phileas");
        final AbstractFilterResult filterResult;

        // Pass a non-null context to Phileas; a no-context request uses an empty context name.
        final String effectiveContextName = useContext ? contextName : "";

        if(mimeType == MimeType.TEXT_PLAIN) {

            final String plainText = new String(body);
            filterResult = plainTextFilterService.filter(phileasPolicy, phileasContextService, vectorService, effectiveContextName, plainText, options);

        } else if(mimeType == MimeType.APPLICATION_PDF) {

            final int pageCount = PdfInputValidator.validate(body);
            if (pagePolicies != null) {
                try {
                    pagePolicies.phileas().validate(pageCount);
                } catch (final IllegalArgumentException e) {
                    throw new BadRequestException(e.getMessage());
                }
            }
            final PdfFilterService pdfFilterService = pdfFilterService(disambiguationEnabled);
            try (PdfRenderSlots.Slot ignored = pdfSlot(queued)) {
                filterResult = pdfFilterService.filter(phileasPolicy, phileasContextService, vectorService, effectiveContextName, body,
                        MimeType.APPLICATION_PDF, pagePolicies == null ? null : pagePolicies.phileas(), options);
            }

        } else if(mimeType == MimeType.APPLICATION_DOCX || mimeType == MimeType.APPLICATION_XLSX) {

            OfficeInputValidator.validate(body, OfficeInputValidator.declaredType(mimeType));
            filterResult = ooxmlFilterService(disambiguationEnabled).filter(phileasPolicy, phileasContextService, vectorService, effectiveContextName, body, mimeType, options);

        } else if(mimeType == MimeType.MESSAGE_RFC822 || mimeType == MimeType.APPLICATION_VND_MS_OUTLOOK) {

            final EmailInputValidator.Inspection inspection =
                    EmailInputValidator.validate(body, EmailInputValidator.declaredFormat(mimeType));
            // A PDF attachment under attachments: redact is rendered as any PDF is, so it waits for a PDF slot.
            final boolean rendersPdf = inspection.hasPdfAttachment() && phileasPolicy.getConfig() != null
                    && phileasPolicy.getConfig().getEmail().getAttachments() == Email.Attachments.REDACT;
            try (PdfRenderSlots.Slot ignored = rendersPdf ? pdfSlot(queued) : null) {
                filterResult = emailFilterService(disambiguationEnabled).filter(phileasPolicy, phileasContextService,
                        vectorService, effectiveContextName, body, MimeType.MESSAGE_RFC822, options);
            } catch (final EncryptedEmailException e) {
                throw new BadRequestException(e.getMessage());
            }

        } else if(mimeType == MimeType.IMAGE_JPEG) {

            final PdfFilterService pdfFilterService = pdfFilterService(disambiguationEnabled);
            try (PdfRenderSlots.Slot ignored = pdfSlot(queued)) {
                filterResult = pdfFilterService.filter(phileasPolicy, phileasContextService, vectorService, effectiveContextName, body, MimeType.IMAGE_JPEG, options);
            }

        } else {

            throw new Exception("Unsupported MIME type: " + mimeType);

        }

        beforePublication.run();

        // Store the ledger from the redaction, but only when the request's context has the
        // redaction ledger enabled. The flag is per context and defaults to off.
        if (ledgerEnabled) {

            // Normalized once so the genesis entry and the per-redaction entries cannot disagree.
            final String ledgerFilename = (filename == null || filename.isBlank()) ? NO_FILENAME : filename;

            LOGGER.info("Initializing the ledger");
            // The genesis entry records the governing policy so the whole chain reflects which policy
            // version applied.
            if (effective == null) {
                ledgerService.initializeLedger(userEntity.getId(), documentId, DigestUtils.sha256Hex(body), ledgerFilename,
                        appliedPolicy.name(), appliedPolicy.version(), appliedPolicy.contentHash());
            } else {
                ledgerService.initializeLedger(userEntity.getId(), documentId, DigestUtils.sha256Hex(body), ledgerFilename,
                        appliedPolicy.name(), appliedPolicy.version(), appliedPolicy.contentHash(), pinnedPolicy.effectiveHash());
            }

            LOGGER.info("Persisting ledger entries: " + filterResult.getIncrementalRedactions().size());
            for (final IncrementalRedaction incrementalRedaction : filterResult.getIncrementalRedactions()) {

                final LedgerEntity ledgerEntity = new LedgerEntity();
                ledgerEntity.setDocumentId(documentId);
                ledgerEntity.setReplacement(incrementalRedaction.getSpan().getReplacement());
                ledgerEntity.setToken(incrementalRedaction.getSpan().getText());
                ledgerEntity.setStartPosition(incrementalRedaction.getSpan().getCharacterStart());
                ledgerEntity.setUserId(userEntity.getId());
                ledgerEntity.setDocumentHash(incrementalRedaction.getHash());
                ledgerEntity.setPreviousHash(ledgerService.getLatestTransaction(userEntity.getId(), documentId).getHash());
                ledgerEntity.setTimestamp(new Date());
                ledgerEntity.setFilename(ledgerFilename);
                ledgerEntity.setType(incrementalRedaction.getSpan().getFilterType().getType());
                // Stamp the governing policy version onto the entry as tamper-evident provenance: the policy of the
                // page set the redaction was found on, else the request's policy.
                final AppliedPolicy entryPolicy = pagePolicies == null ? appliedPolicy
                        : pagePolicies.byLabel().getOrDefault(incrementalRedaction.getSpan().getPageAssignment(), appliedPolicy);
                ledgerEntity.setPolicyName(entryPolicy.name());
                ledgerEntity.setPolicyVersion(entryPolicy.version());
                ledgerEntity.setPolicyContentHash(entryPolicy.contentHash());
                ledgerEntity.setEffectiveHash(effective == null ? null : pinnedPolicy.effectiveHash());

                // Compute and set this entry's hash so the chain is actually formed (previous -> current)
                // and validates; previousHash above links it to the prior entry.
                ledgerEntity.setHash(ledgerEntity.calculateHash());

                ledgerService.addTransaction(ledgerEntity);

            }
            ledgerService.completeChain(userEntity.getId(), documentId);

        } else {
            LOGGER.debug("Redaction ledger disabled for user; skipping ledger write.");
        }

        final long tokenCount = filterResult.getTokens();

        LOGGER.debug("Redaction request processed successfully. Tokens: {}, Redactions: {}", tokenCount, filterResult.getExplanation().appliedSpans().size());

        // Publish redaction metrics to Micrometer (scraped via /actuator/prometheus).
        recordRedactionMetrics(userEntity.getId(), filterResult);

        // Audit that the document redaction completed. The generated documentId serves as the
        // request correlation id, and the number of redactions is recorded as a detail.
        auditEventPublisher.auditEvent(documentId, AuditLogEvent.DOCUMENT_REDACTION_COMPLETED,
                userEntity.getId(), null, null,
                "redactions: " + filterResult.getExplanation().appliedSpans().size()
                        + ", policy: " + AuditDetails.quote(appliedPolicy.name()) + ", policyVersion: " + appliedPolicy.version()
                        + (pagePolicies == null ? "" : ", pagePolicies: " + AuditDetails.quote(PagePolicyRequest.describe(pagePolicies.applied()))));

        // A queued document's selection was drawn and recorded when it was accepted; see selectForReview.
        if (!queued && filterResult.isReviewSampled()) {
            auditReviewSampled(userEntity.getId(), documentId, appliedPolicy);
        }

        // A queued document has no request being served, so its record is correlated by the document ID, as the
        // worker's other events are.
        recordLeftoverCheck(queued ? documentId : currentRequestId(), userEntity.getId(), documentId, appliedPolicy,
                filterResult.getLeftoverCheck());

        return new RedactionOutcome(documentId, filterResult, appliedPolicy,
                pagePolicies == null ? List.of() : pagePolicies.applied());

        } finally {
            // Release the per-request cache (closes the Valkey/Redis pool when one is configured).
            contextCache.close();
        }

    }

    /**
     * Draws review selection for a queued document when it is accepted, with the policy captured for it, so the
     * 202 response can report it. The worker's later draw is not used.
     */
    public boolean selectForReview(final String effectiveJson) {
        final EffectiveConfiguration effective = gson.fromJson(effectiveJson, EffectiveConfiguration.class);
        return ReviewSampler.isSelected(gson.fromJson(effective.policyJson(), Policy.class));
    }

    static final String LEFTOVER_CHECK_OFF = "leftover_check=true requests the policy's leftover check, but the policy's "
            + "config.review.leftoverCheck.mode is off, so no check would run. Omit leftover_check, or set the mode to "
            + "onRequest or always.";

    /** Refuses a request for the leftover check under a policy whose mode is off, so no caller thinks one ran. */
    static void refuseLeftoverCheckIfOff(final Policy policy, final FilterOptions options) {
        if (options != null && options.isLeftoverCheckRequested() && leftoverCheckMode(policy) == LeftoverCheck.Mode.OFF) {
            throw new BadRequestException(LEFTOVER_CHECK_OFF);
        }
    }

    /** As {@link #refuseLeftoverCheckIfOff(Policy, FilterOptions)}, with the policy captured for a queued document. */
    public void refuseLeftoverCheckIfOff(final String effectiveJson, final FilterOptions options) {
        final EffectiveConfiguration effective = gson.fromJson(effectiveJson, EffectiveConfiguration.class);
        refuseLeftoverCheckIfOff(gson.fromJson(effective.policyJson(), Policy.class), options);
    }

    private static LeftoverCheck.Mode leftoverCheckMode(final Policy policy) {
        if (policy.getConfig() == null || policy.getConfig().getReview() == null) {
            return LeftoverCheck.Mode.OFF;
        }
        return policy.getConfig().getReview().getLeftoverCheck().getMode();
    }

    /*
     * Counts each check by its outcome and each finding by its type, and records a redaction_leftovers_found audit
     * event when the check found anything. Neither carries a finding's text or a policy name.
     */
    void recordLeftoverCheck(final String requestId, final String userId, final String documentId,
                             final AppliedPolicy appliedPolicy, final LeftoverCheckResult check) {
        if (ResultHeaders.notChecked(check)) {
            meterRegistry.counter("philter.leftover.checks", "outcome", "not_checked").increment();
            return;
        }
        // Not called for: nothing to count.
        if (check == null || !check.isPerformed()) {
            return;
        }
        final boolean found = check.getFindingCount() > 0;
        meterRegistry.counter("philter.leftover.checks", "outcome", found ? "found" : "none_found").increment();
        if (!found) {
            return;
        }
        final java.util.SortedSet<String> types = new java.util.TreeSet<>();
        for (final LeftoverFinding finding : check.getFindings()) {
            final String type = finding.getFilterType().getType();
            types.add(type);
            meterRegistry.counter("philter.leftover.findings", "filter_type", type).increment();
        }
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACTION_LEFTOVERS_FOUND, userId, null, null,
                "documentId: " + AuditDetails.quote(documentId) + ", policy: " + AuditDetails.quote(appliedPolicy.name())
                        + ", policyVersion: " + appliedPolicy.version() + ", count: " + check.getFindingCount()
                        + ", types: " + AuditDetails.quote(String.join(", ", types)));
    }

    /** Records that the policy's config.review.samplingRate selected the request being served for review. */
    public void auditReviewSampled(final String userId, final String documentId, final AppliedPolicy appliedPolicy) {
        auditEventPublisher.auditEvent(currentRequestId(), AuditLogEvent.REDACTION_REVIEW_SAMPLED, userId, null, null,
                "documentId: " + AuditDetails.quote(documentId) + ", policy: " + AuditDetails.quote(appliedPolicy.name())
                        + ", policyVersion: " + appliedPolicy.version());
    }

    // The X-Request-Id of the request being served. Only absent when called outside a request, as in a unit test.
    private static String currentRequestId() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof final org.springframework.web.context.request.ServletRequestAttributes attributes
                && attributes.getRequest().getAttribute(ai.philterd.philter.api.controllers.AbstractApiController.REQUEST_ID_ATTRIBUTE)
                instanceof final String requestId) {
            return requestId;
        }
        return null;
    }

    // The user's current policy by name, from the in-process cache while it holds the stored revision, else the
    // database. The revision is checked without reading the policy, so an edit made on any instance governs the next
    // request. The JSON can contain PII in filter-strategy conditions, which is why the cache is in-process only.
    private RedactionCache.CachedPolicy storedPolicy(final String policyName, final String userId) throws PolicyNotFoundException {
        // A managed policy has no owner, so it is not in the user's cache or found by the user's revision.
        if (PolicyDataService.isManagedName(policyName)) {
            final PolicyEntity managed = policyDataService.findOneOrManaged(policyName, userId);
            if (managed == null) {
                throw new PolicyNotFoundException("The policy '" + policyName + "' does not exist.");
            }
            return new RedactionCache.CachedPolicy(managed.getPolicy(), managed.getRevision());
        }
        final Integer storedRevision = policyDataService.findRevision(policyName, userId);
        if (storedRevision == null) {
            throw new PolicyNotFoundException("The policy '" + policyName + "' does not exist.");
        }
        final RedactionCache.CachedPolicy cached = redactionCache.getPolicy(userId, policyName);
        if (cached != null && cached.getRevision() == storedRevision) {
            return cached;
        }
        final PolicyEntity policyEntity = policyDataService.findOne(policyName, userId);
        if (policyEntity == null) {
            throw new PolicyNotFoundException("The policy '" + policyName + "' does not exist.");
        }
        redactionCache.putPolicy(userId, policyName, policyEntity.getPolicy(), policyEntity.getRevision());
        return new RedactionCache.CachedPolicy(policyEntity.getPolicy(), policyEntity.getRevision());
    }

    /** Page sets' policies for Phileas, the version of each for the response, and each by its span label for the ledger. */
    private record PagePolicies(PdfPagePolicies phileas, List<AppliedPagePolicy> applied, Map<String, AppliedPolicy> byLabel) {
    }

    // The current version of each requested page set's policy, or null when none was requested.
    private PagePolicies resolvePagePolicies(final List<PagePolicyRequest> requests, final UserEntity userEntity)
            throws PolicyNotFoundException {
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        final List<PagePolicySnapshot> snapshots = new java.util.ArrayList<>();
        for (final PagePolicyRequest request : requests) {
            final RedactionCache.CachedPolicy stored = storedPolicy(request.policyName(), userEntity.getId());
            snapshots.add(new PagePolicySnapshot(request.pages(), request.policyName(), stored.getRevision(),
                    PolicyVersionDataService.contentHash(stored.getPolicyJson()),
                    gson.toJson(resolveEffectivePolicy(stored.getPolicyJson(), userEntity))));
        }
        return pinnedPagePolicies(snapshots);
    }

    private PagePolicies pinnedPagePolicies(final List<PagePolicySnapshot> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        final PdfPagePolicies phileas = new PdfPagePolicies();
        final List<AppliedPagePolicy> applied = new java.util.ArrayList<>();
        final Map<String, AppliedPolicy> byLabel = new java.util.HashMap<>();
        for (final PagePolicySnapshot snapshot : snapshots) {
            final Policy policy = gson.fromJson(snapshot.policyJson(), Policy.class);
            final String refusal = PolicyLoadCheck.shared().refusal(policy);
            if (refusal != null) {
                throw new ai.philterd.philter.services.policies.PolicyResolutionException(
                        "The policy '" + snapshot.name() + "': " + refusal);
            }
            // Labeled with the page set, so a policy can be assigned to more than one, and the policy named default
            // does not take the label Phileas reserves for unassigned pages.
            phileas.assign(snapshot.pages(), policy, snapshot.pages());
            final AppliedPolicy appliedPolicy = new AppliedPolicy(snapshot.name(), snapshot.version(), snapshot.contentHash());
            applied.add(new AppliedPagePolicy(snapshot.pages(), appliedPolicy));
            byLabel.put(snapshot.pages(), appliedPolicy);
        }
        return new PagePolicies(phileas, List.copyOf(applied), Map.copyOf(byLabel));
    }

    /**
     * Publishes redaction metrics for a completed filter result to Micrometer: a token counter and a
     * per-filter-type redaction counter. Exposed at package scope so the counter emission can be
     * tested with a real {@link MeterRegistry} without standing up the full filtering pipeline.
     */
    void recordRedactionMetrics(final String userId, final AbstractFilterResult filterResult) {

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
        piiCountAggregatePublisher.record(userId, filterResult.getContext(), filterTypeCounts.keySet());

    }

    private Policy resolveEffectivePolicy(final String policyJson, final UserEntity userEntity) {
        // Resolve the account FPE key and derive its tweak. FF3-1
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

        // Get the always/never redact lists, from the in-process cache while it holds the stored revisions, else the
        // database. Checked before every request, so a change made on any instance applies to the next one.
        final RedactListsDataService.Revisions storedRevisions = redactListsService.revisions(userEntity.getId());
        RedactionCache.CachedRedactLists cachedRedactLists = redactionCache.getRedactLists(userEntity.getId());
        if (cachedRedactLists == null
                || !cachedRedactLists.isAt(storedRevisions.alwaysRedact(), storedRevisions.neverRedact())) {
            final RedactListsEntity entity = redactListsService.find(userEntity.getId());
            // Cached at the revisions read with the terms, so a write between the two reads is caught next time.
            cachedRedactLists = entity == null
                    ? new RedactionCache.CachedRedactLists(List.of(), List.of(), 0, 0)
                    : new RedactionCache.CachedRedactLists(entity.getTermsToAlwaysRedact(), entity.getTermsToNeverRedact(),
                            entity.getAlwaysRedactRevision(), entity.getNeverRedactRevision());
            redactionCache.putRedactLists(userEntity.getId(), cachedRedactLists);
        }

        // Reconstruct the entity shape the rest of this method expects (never null; lists may be empty).
        final RedactListsEntity redactListsEntity = new RedactListsEntity();
        redactListsEntity.setTermsToAlwaysRedact(cachedRedactLists.getAlwaysRedact());
        redactListsEntity.setTermsToNeverRedact(cachedRedactLists.getNeverRedact());

        // Add in the never-redact (ignore) list.
        if(!redactListsEntity.getTermsToNeverRedact().isEmpty()) {
            final Ignored neverRedactIgnored = new Ignored("never-redact-list", redactListsEntity.getTermsToNeverRedact(), Collections.emptyList(), false);
            final var ignored = new java.util.ArrayList<>(phileasPolicy.getIgnored());
            ignored.add(neverRedactIgnored);
            phileasPolicy.setIgnored(ignored);
        }

        // Add in the always-redact list.
        if(!redactListsEntity.getTermsToAlwaysRedact().isEmpty()) {

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

        ai.philterd.philter.services.policies.EffectiveConfigurationLimits.requireSize(gson.toJson(phileasPolicy));
        return phileasPolicy;
    }

    public String captureEffectiveConfiguration(final String userId, final PolicyEntity policy, final String contextName) {
        return captureEffectiveConfiguration(userId, policy, contextName, List.of());
    }

    /** A page set's stored policy and the content hash of its retained snapshot, for a queued PDF. */
    public record PagePolicyCapture(String pages, PolicyEntity policy, String contentHash) {
    }

    /**
     * The policy, its lists, and the context, resolved for the user the document is redacted for. That is the
     * user, not the policy's owner, since a managed policy has none.
     */
    public String captureEffectiveConfiguration(final String userId, final PolicyEntity policy, final String contextName,
                                                final List<PagePolicyCapture> pagePolicies) {
        final UserEntity user = userService.findOneById(userId);
        if (user == null) throw new IllegalStateException("Account no longer exists.");
        final Policy resolved = resolveEffectivePolicy(policy.getPolicy(), user);
        // Refused when the document is submitted, not left to fail in the worker after it was accepted.
        final String refusal = PolicyLoadCheck.shared().refusal(resolved);
        if (refusal != null) {
            throw new ai.philterd.philter.services.policies.PolicyResolutionException(refusal);
        }
        final ContextEntity context = contextName == null || contextName.isBlank() ? null
                : contextService.findOneByNameAndUserId(contextName, user.getId());
        if (contextName != null && !contextName.isBlank() && context == null) {
            throw new ai.philterd.philter.services.policies.PolicyResolutionException("Context does not exist.");
        }
        final com.google.gson.JsonElement json = gson.toJsonTree(resolved);
        freezeEnvironmentKeys(json);
        final List<PagePolicySnapshot> pages = new java.util.ArrayList<>();
        for (final PagePolicyCapture page : pagePolicies) {
            final Policy pagePolicy = resolveEffectivePolicy(page.policy().getPolicy(), user);
            final String pageRefusal = PolicyLoadCheck.shared().refusal(pagePolicy);
            if (pageRefusal != null) {
                throw new ai.philterd.philter.services.policies.PolicyResolutionException(
                        "The policy '" + page.policy().getName() + "': " + pageRefusal);
            }
            final com.google.gson.JsonElement pageJson = gson.toJsonTree(pagePolicy);
            freezeEnvironmentKeys(pageJson);
            pages.add(new PagePolicySnapshot(page.pages(), page.policy().getName(), page.policy().getRevision(),
                    page.contentHash(), gson.toJson(pageJson)));
        }
        final String captured = gson.toJson(new EffectiveConfiguration(gson.toJson(json),
                context == null ? null : ContextSnapshots.toJson(context), pages.isEmpty() ? null : pages));
        ai.philterd.philter.services.policies.EffectiveConfigurationLimits.requireSize(captured);
        return captured;
    }

    private void freezeEnvironmentKeys(com.google.gson.JsonElement element) {
        if (element.isJsonArray()) { element.getAsJsonArray().forEach(this::freezeEnvironmentKeys); return; }
        if (!element.isJsonObject()) return;
        for (var entry : element.getAsJsonObject().entrySet()) {
            var value = entry.getValue();
            if ((entry.getKey().equals("key") || entry.getKey().equals("tweak")) && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isString() && value.getAsString().startsWith("env:")) {
                // Only a variable in POLICY_ALLOWED_ENV_KEYS. PolicyLoadCheck refuses any other before this.
                final String resolved;
                try {
                    resolved = ai.philterd.phileas.policy.PolicyEnvironment.resolve("policy key", value.getAsString());
                } catch (final ai.philterd.phileas.policy.InvalidPolicyEnvironmentException e) {
                    throw new ai.philterd.philter.services.policies.PolicyResolutionException(e.getMessage());
                }
                if (resolved == null || resolved.isBlank()) throw new ai.philterd.philter.services.policies.PolicyResolutionException("Required environment key is unavailable.");
                entry.setValue(new com.google.gson.JsonPrimitive(resolved));
            } else freezeEnvironmentKeys(value);
        }
    }

}
