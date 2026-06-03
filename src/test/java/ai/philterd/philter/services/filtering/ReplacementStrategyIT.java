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
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.security.ChaChaRandom;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.context.MongoContextService;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import ai.philterd.philter.services.policies.SimplifiedStrategy;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hc.client5.http.classic.HttpClient;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * End-to-end tests proving that the newly exposed standard replacement strategies — {@code
 * STATIC_REPLACE} and {@code HASH_SHA256_REPLACE} — actually take effect through the real Phileas
 * filter engine when configured on a {@link SimplifiedPolicy}. The policy is converted with the
 * production {@link SimplifiedPolicy#toPolicy} path, so this covers both the strategy mapping and the
 * parameter plumbing ({@code staticReplacement}, {@code salt}) that {@code toPolicy} now applies.
 *
 * <p>A rule-based filter type (SSN) is used so the engine runs entirely locally with no external model
 * dependency, keeping the tests deterministic and CI-friendly.
 */
class ReplacementStrategyIT extends AbstractMongoIT {

    private static final String SSN = "123-45-6789";
    private static final String TEXT = "Patient SSN " + SSN + " is on file.";

    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void setUpServices() {
        auditEventPublisher = mock(AuditEventPublisher.class);
    }

    @Test
    void staticReplaceSubstitutesTheConfiguredValue() throws Exception {

        final Policy policy = policyWith(FilterType.SSN, "STATIC_REPLACE",
                Map.of(SimplifiedPolicy.PARAM_STATIC_REPLACEMENT, "REDACTED-SSN"));

        final TextFilterResult result = redact(policy, TEXT);

        assertEquals("REDACTED-SSN", replacementOf(result, FilterType.SSN),
                "the SSN must be replaced with the configured static value");
        assertTrue(result.getFilteredText().contains("REDACTED-SSN"));
        assertFalse(result.getFilteredText().contains(SSN), "the original SSN must not survive in the output");
    }

    @Test
    void hashSha256UnsaltedProducesThePlainSha256OfTheToken() throws Exception {

        final Policy policy = policyWith(FilterType.SSN, "HASH_SHA256_REPLACE", Map.of());

        final TextFilterResult result = redact(policy, TEXT);

        assertEquals(DigestUtils.sha256Hex(SSN), replacementOf(result, FilterType.SSN),
                "an unsalted SHA-256 replacement must equal the plain SHA-256 hex of the token");
    }

    @Test
    void hashSha256SaltedProducesADifferentHash() throws Exception {

        final Policy policy = policyWith(FilterType.SSN, "HASH_SHA256_REPLACE",
                Map.of(SimplifiedPolicy.PARAM_SALT, "true"));

        final String salted = replacementOf(redact(policy, TEXT), FilterType.SSN);

        assertTrue(salted.matches("[a-f0-9]{64}"), "a salted SHA-256 replacement is still 64 hex characters");
        assertNotEquals(DigestUtils.sha256Hex(SSN), salted, "the salt must change the hash away from the plain SHA-256");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static Policy policyWith(final FilterType filterType, final String strategy,
                                     final Map<String, String> parameters) throws Exception {

        final SimplifiedPolicy policy = new SimplifiedPolicy();
        policy.setFilters(Map.of(filterType, List.of(
                new SimplifiedStrategy(strategy, parameters, AnonymizationMethod.UUID))));

        return policy.toPolicy("0123456789ABCDEF0123456789ABCDEF", "0123456789ABCDEF", null, null);
    }

    private TextFilterResult redact(final Policy policy, final String text) throws Exception {

        final Properties properties = new Properties();
        properties.put("incremental.redactions.enabled", "true");
        properties.put("span.disambiguation.enabled", "false");
        final PhileasConfiguration configuration = new PhileasConfiguration(properties);

        final MongoContextService contextService = new MongoContextService(
                mongoClient, new ContextCache(null, 0, null, false), new ObjectId(), "ctx", auditEventPublisher);

        final PlainTextFilterService filterService = new PlainTextFilterService(
                configuration, contextService, new NoOpVectorService(), new ChaChaRandom(), mock(HttpClient.class));

        return filterService.filter(policy, "ctx", text);
    }

    private static String replacementOf(final TextFilterResult result, final FilterType filterType) {
        final List<Span> spans = result.getExplanation().appliedSpans().stream()
                .filter(span -> span.getFilterType() == filterType)
                .toList();
        assertEquals(1, spans.size(), "expected exactly one " + filterType + " span");
        return spans.get(0).getReplacement();
    }

}
