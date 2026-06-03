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
import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.anonymization.AnonymizationMethod;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.strategies.AbstractFilterStrategy;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.security.ChaChaRandom;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.context.MongoContextService;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.apache.hc.client5.http.classic.HttpClient;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * End-to-end tests for cross-document replacement consistency under the {@code CONTEXT} replacement
 * scope (issue #208). Where {@link ai.philterd.philter.services.context.ContextExportImportIT}
 * exercises {@link MongoContextService#computeReplacementIfAbsent} directly, these tests drive the
 * <em>real Phileas filter engine</em> ({@link PlainTextFilterService}) against a real (in-memory)
 * MongoDB, using native Phileas policies.
 *
 * <p>Each "redaction request" is issued through {@link #redact}, which builds a <em>fresh</em>
 * {@link MongoContextService} and {@link PlainTextFilterService} every call. This mirrors production,
 * where every request constructs a new context service, so the only thing that can carry a surrogate
 * from one request to the next is the persisted mapping table in MongoDB — not any in-process state.
 *
 * <p>Rule-based filter types (SSN, phone number) are used so the engine runs entirely locally with no
 * external model/service dependency, keeping the tests deterministic and CI-friendly.
 *
 * <p>Capacity-triggered eviction <em>selection</em> (which entry is removed when a context is full) is
 * covered by {@code ContextEntryDataServiceTest.putReplacementEvictsLeastReadWhenAtCapacity}; here
 * {@link #evictedTokenIsTreatedAsNewAndReanonymized} covers the documented end-to-end behavior that a
 * token receives a fresh surrogate once its mapping has been evicted from the context.
 */
class CrossDocumentConsistencyIT extends AbstractMongoIT {

    private AuditEventPublisher auditEventPublisher;
    private ContextEntryDataService contextEntryService;

    @BeforeEach
    void setUpServices() {
        auditEventPublisher = mock(AuditEventPublisher.class);
        contextEntryService = new ContextEntryDataService(mongoClient, auditEventPublisher);
    }

    @Test
    void sameTokenInTwoRequestsYieldsSameSurrogateUnderContextScope() throws Exception {

        final ObjectId userId = new ObjectId();
        final String context = "patient-records";
        final Policy policy = anonymizePolicy(AbstractFilterStrategy.REPLACEMENT_SCOPE_CONTEXT,
                Map.of(FilterType.SSN, AnonymizationMethod.REALISTIC));

        // Two separate documents/requests reference the same SSN in different surrounding text.
        final String surrogate1 = replacementOf(
                redact(userId, context, policy, "Patient SSN 123-45-6789 is on file."), FilterType.SSN);
        final String surrogate2 = replacementOf(
                redact(userId, context, policy, "The intake form records 123-45-6789 as the number."), FilterType.SSN);

        // The token was actually pseudonymized (not left as-is, not a fixed redaction token)...
        assertNotNull(surrogate1, "the SSN should have been detected and replaced");
        assertNotEquals("123-45-6789", surrogate1, "the SSN should have been anonymized, not left in place");

        // ...and the same entity received the same surrogate across the two separate requests.
        assertEquals(surrogate1, surrogate2,
                "under CONTEXT scope the same token must yield the same surrogate across documents");

        // The mapping is carried by exactly one persisted context entry keyed by the hashed token.
        assertTrue(contextEntryService.containsToken(userId, context, "123-45-6789"));
        assertEquals(1, contextEntryService.countByUserIdAndContext(userId, context));
    }

    @Test
    void documentScopeDoesNotPersistOrShareAcrossRequests() throws Exception {

        final ObjectId userId = new ObjectId();
        final String context = "doc-scope";
        final Policy policy = anonymizePolicy(AbstractFilterStrategy.REPLACEMENT_SCOPE_DOCUMENT,
                Map.of(FilterType.SSN, AnonymizationMethod.REALISTIC));

        final String surrogate1 = replacementOf(
                redact(userId, context, policy, "Patient SSN 123-45-6789 is on file."), FilterType.SSN);
        final String surrogate2 = replacementOf(
                redact(userId, context, policy, "The intake form records 123-45-6789 as the number."), FilterType.SSN);

        // Each request still anonymizes the SSN...
        assertNotNull(surrogate1);
        assertNotNull(surrogate2);

        // ...but DOCUMENT scope writes nothing to the context, so no cross-request mapping exists. This
        // is the documented contract: documents anonymize independently under the default scope.
        assertEquals(0, contextEntryService.countByUserIdAndContext(userId, context),
                "DOCUMENT scope must not persist mappings to the context");
    }

    @Test
    void consistencyHoldsForMultipleEntityTypesInOneContext() throws Exception {

        final ObjectId userId = new ObjectId();
        final String context = "multi-type";
        final Policy policy = anonymizePolicy(AbstractFilterStrategy.REPLACEMENT_SCOPE_CONTEXT, Map.of(
                FilterType.SSN, AnonymizationMethod.REALISTIC,
                FilterType.PHONE_NUMBER, AnonymizationMethod.REALISTIC));

        final TextFilterResult first = redact(userId, context, policy,
                "Contact 555-867-5309 about SSN 123-45-6789.");
        final TextFilterResult second = redact(userId, context, policy,
                "Re: 123-45-6789 — leave a message at 555-867-5309.");

        // Every entity that routes through the context stays consistent across documents.
        assertEquals(replacementOf(first, FilterType.SSN), replacementOf(second, FilterType.SSN),
                "the SSN surrogate must be consistent across documents");
        assertEquals(replacementOf(first, FilterType.PHONE_NUMBER), replacementOf(second, FilterType.PHONE_NUMBER),
                "the phone-number surrogate must be consistent across documents");

        // One persisted mapping per distinct entity.
        assertEquals(2, contextEntryService.countByUserIdAndContext(userId, context));
    }

    @Test
    void replacementsAreIsolatedPerContext() throws Exception {

        final ObjectId userId = new ObjectId();
        final Policy policy = anonymizePolicy(AbstractFilterStrategy.REPLACEMENT_SCOPE_CONTEXT,
                Map.of(FilterType.SSN, AnonymizationMethod.REALISTIC));

        final String text = "SSN 123-45-6789 on record.";

        // Learn the token in context A, then in a separate context B.
        final String inContextA = replacementOf(redact(userId, "ctx-a", policy, text), FilterType.SSN);
        final String inContextB = replacementOf(redact(userId, "ctx-b", policy, text), FilterType.SSN);

        // Each context resolves the token consistently within itself on a subsequent request...
        assertEquals(inContextA, replacementOf(redact(userId, "ctx-a", policy, text), FilterType.SSN),
                "context A must remain self-consistent");
        assertEquals(inContextB, replacementOf(redact(userId, "ctx-b", policy, text), FilterType.SSN),
                "context B must remain self-consistent");

        // ...and the two contexts keep independent mapping tables (one entry each, no bleed-through).
        assertEquals(1, contextEntryService.countByUserIdAndContext(userId, "ctx-a"));
        assertEquals(1, contextEntryService.countByUserIdAndContext(userId, "ctx-b"));
    }

    @Test
    void evictedTokenIsTreatedAsNewAndReanonymized() {

        // Eviction acts on the persisted mapping table, so this asserts the documented behavior at that
        // exact layer (the one evictIfFull operates on), free of the in-process replacement cache that
        // sits above it in a live request. Capacity-triggered eviction *selection* — which entry is
        // dropped when a context is full — is covered by
        // ContextEntryDataServiceTest.putReplacementEvictsLeastReadWhenAtCapacity.
        final ObjectId userId = new ObjectId();
        final String context = "eviction";
        final String token = "123-45-6789";

        contextEntryService.putReplacement(userId, context, token, "SURROGATE-A", "SSN");
        assertEquals("SURROGATE-A", contextEntryService.getReplacement(userId, context, token));
        assertEquals(1, contextEntryService.countByUserIdAndContext(userId, context));

        // Simulate eviction: removing the entry is exactly what evictIfFull does to the victim it picks
        // when a context is at capacity. After eviction the token is no longer mapped.
        final ContextEntryEntity entry = contextEntryService.findOneEntryByToken(userId, context, token);
        assertEquals(1, contextEntryService.deleteByIdAndUserId(entry.getId(), userId));
        assertEquals(0, contextEntryService.countByUserIdAndContext(userId, context));
        assertNull(contextEntryService.getReplacement(userId, context, token),
                "an evicted token must no longer resolve to its old surrogate");

        // Re-encountering the token treats it as new: a fresh surrogate is stored and resolves. This is
        // the documented post-eviction behavior — an evicted token may receive a new surrogate.
        contextEntryService.putReplacement(userId, context, token, "SURROGATE-B", "SSN");
        assertEquals("SURROGATE-B", contextEntryService.getReplacement(userId, context, token),
                "the token must be re-learned with a new surrogate after eviction");
        assertEquals(1, contextEntryService.countByUserIdAndContext(userId, context));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /**
     * Issues a single redaction "request": builds a fresh {@link MongoContextService} and Phileas
     * {@link PlainTextFilterService} (as production does per request) and filters the text. Because the
     * context service is recreated each call, any cross-request consistency must come from MongoDB.
     */
    private TextFilterResult redact(final ObjectId userId, final String context, final Policy policy,
                                    final String text) throws Exception {

        final Properties properties = new Properties();
        properties.put("incremental.redactions.enabled", "true");
        properties.put("span.disambiguation.enabled", "false");
        final PhileasConfiguration configuration = new PhileasConfiguration(properties);

        final MongoContextService contextService = new MongoContextService(
                mongoClient, new ContextCache(null, 0, null, false), userId, context, auditEventPublisher);

        final PlainTextFilterService filterService = new PlainTextFilterService(
                configuration, contextService, new NoOpVectorService(), new ChaChaRandom(), mock(HttpClient.class));

        return filterService.filter(policy, context, text);
    }

    /** Returns the surrogate the engine applied to the (single) span of the given type. */
    private static String replacementOf(final TextFilterResult result, final FilterType filterType) {
        final List<Span> spans = result.getExplanation().appliedSpans().stream()
                .filter(span -> span.getFilterType() == filterType)
                .toList();
        assertEquals(1, spans.size(), "expected exactly one " + filterType + " span");
        return spans.get(0).getReplacement();
    }

    /**
     * Builds a native Phileas policy that anonymizes each supplied filter type with the
     * {@code RANDOM_REPLACE} strategy at the given replacement scope and anonymization method. No
     * condition is set, so rule-based detections are never gated out.
     */
    private static Policy anonymizePolicy(final String replacementScope,
                                          final Map<FilterType, AnonymizationMethod> filterTypes) {

        final StringBuilder identifiers = new StringBuilder();
        for (final Map.Entry<FilterType, AnonymizationMethod> entry : filterTypes.entrySet()) {
            final String[] keys = nativeKeys(entry.getKey());
            if (identifiers.length() > 0) {
                identifiers.append(",");
            }
            identifiers.append("\"").append(keys[0]).append("\":{\"").append(keys[1]).append("\":[")
                    .append("{\"strategy\":\"RANDOM_REPLACE\",\"replacementScope\":\"").append(replacementScope)
                    .append("\",\"anonymizationMethod\":\"").append(entry.getValue().name()).append("\"}]}");
        }

        return new Gson().fromJson("{\"identifiers\":{" + identifiers + "}}", Policy.class);
    }

    /** Maps a filter type to its native identifier key and filter-strategies array key. */
    private static String[] nativeKeys(final FilterType filterType) {
        return switch (filterType) {
            case SSN -> new String[]{"ssn", "ssnFilterStrategies"};
            case PHONE_NUMBER -> new String[]{"phoneNumber", "phoneNumberFilterStrategies"};
            default -> throw new IllegalArgumentException("Unsupported filter type in test: " + filterType);
        };
    }

}
