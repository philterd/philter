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

import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.phield.PhieldPublisher;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedactionServiceMetricsTest {

    @Mock private MongoClient mongoClient;
    @Mock private PolicyDataService policyDataService;
    @Mock private CustomListDataService customListService;
    @Mock private RedactListsDataService redactListsService;
    @Mock private ContextDataService contextService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private LedgerDataService ledgerService;
    @Mock private UserService userService;
    @Mock private PhieldPublisher phieldPublisher;
    @Mock private PiiCountAggregatePublisher piiCountAggregatePublisher;

    private SimpleMeterRegistry meterRegistry;
    private RedactionService redactionService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        redactionService = new RedactionService(mongoClient, policyDataService, customListService,
                redactListsService, contextService, auditEventPublisher,
                ledgerService, userService, meterRegistry, phieldPublisher, piiCountAggregatePublisher,
                new ai.philterd.philter.services.cache.RedactionCache());
    }

    private static Span appliedSpan(final FilterType filterType) {
        return Span.make(0, 4, filterType, "none", 1.0, "text", "*", "salt", false, true, new String[]{}, 0);
    }

    private static TextFilterResult resultWith(final long tokens, final List<Span> appliedSpans) {
        return new TextFilterResult("redacted", "none", 0,
                new Explanation(appliedSpans, Collections.emptyList()), Collections.emptyList(), tokens);
    }

    @Test
    void recordsTokenCounter() {
        redactionService.recordRedactionMetrics(resultWith(42L, Collections.emptyList()));

        final Counter tokens = meterRegistry.find("philter.tokens").counter();
        assertNotNull(tokens, "philter.tokens counter should exist");
        assertEquals(42.0, tokens.count());
    }

    @Test
    void recordsPerFilterTypeRedactionCounters() {
        final List<Span> spans = new ArrayList<>();
        spans.add(appliedSpan(FilterType.PERSON));
        spans.add(appliedSpan(FilterType.PERSON));
        spans.add(appliedSpan(FilterType.SSN));

        redactionService.recordRedactionMetrics(resultWith(10L, spans));

        final Counter personCounter = meterRegistry.find("philter.redactions")
                .tag("filter_type", FilterType.PERSON.getType())
                .counter();
        final Counter ssnCounter = meterRegistry.find("philter.redactions")
                .tag("filter_type", FilterType.SSN.getType())
                .counter();

        assertNotNull(personCounter, "person redaction counter should exist");
        assertNotNull(ssnCounter, "ssn redaction counter should exist");
        assertEquals(2.0, personCounter.count());
        assertEquals(1.0, ssnCounter.count());
    }

    @Test
    void noRedactionsEmitsTokenCounterButNoTypeCounters() {
        redactionService.recordRedactionMetrics(resultWith(0L, Collections.emptyList()));

        assertNotNull(meterRegistry.find("philter.tokens").counter());
        // With no applied spans there are no per-filter-type counters.
        assertNull(meterRegistry.find("philter.redactions").counter());
        assertEquals(0, meterRegistry.find("philter.redactions").counters().size());
    }

}
