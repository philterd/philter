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
package ai.philterd.philter.api.filters.auth;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.google.gson.Gson;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAuthenticationFilterMetricsTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private SimpleMeterRegistry meterRegistry;
    private ApiAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("api_keys")).thenReturn(mongoCollection);

        // A valid, non-deleted API key document so the filter authorizes the request.
        final Document apiKeyDocument = new Document("_id", new ObjectId())
                .append("user_id", new ObjectId())
                .append("api_key_hash", EncryptionService.hashSha256(API_KEY))
                .append("api_key_prefix", "sk_abcdefghij...")
                .append("deleted", false)
                .append("timestamp", new Date());

        final FindIterable<Document> findIterable = org.mockito.Mockito.mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(apiKeyDocument);

        meterRegistry = new SimpleMeterRegistry();
        filter = new ApiAuthenticationFilter(mongoClient, auditEventPublisher, meterRegistry, new Gson());
    }

    @Test
    void authenticatedApiRequestIncrementsRequestCounterWithMethodAndStatusTags() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/filter");
        request.setMethod("POST");
        request.addHeader("Authorization", "Bearer " + API_KEY);

        final MockHttpServletResponse response = new MockHttpServletResponse();

        // Downstream sets a 200 status, which the filter should record as the status tag.
        final FilterChain chain = (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200);

        filter.doFilter(request, response, chain);

        final Counter counter = meterRegistry.find("philter.api.requests")
                .tag("method", "POST")
                .tag("status", "200")
                .counter();

        assertNotNull(counter, "philter.api.requests counter with method=POST,status=200 should exist");
        assertEquals(1.0, counter.count());
    }

    @Test
    void unauthorizedRequestDoesNotReachTheRequestCounter() throws Exception {
        // An invalidly-formatted key is rejected before the metered chain.doFilter block.
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/filter");
        request.setMethod("POST");
        request.addHeader("Authorization", "Bearer not-a-valid-key");

        final MockHttpServletResponse response = new MockHttpServletResponse();
        final FilterChain chain = org.mockito.Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        // No request counter is emitted for a request rejected before authentication succeeds.
        assertEquals(0, meterRegistry.find("philter.api.requests").counters().size());
        // The chain is never invoked for an unauthorized request.
        verify(chain, org.mockito.Mockito.never()).doFilter(any(), any());
        // The failed authentication is audited.
        verify(auditEventPublisher).auditEvent(any(), eq(ai.philterd.philter.model.AuditLogEvent.API_AUTHENTICATION_FAILED),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                any(), org.mockito.ArgumentMatchers.contains("malformed"));
    }

    @Test
    void statusEndpointIsNotMetered() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/status");
        request.setMethod("GET");

        final MockHttpServletResponse response = new MockHttpServletResponse();
        final boolean[] chainCalled = {false};
        final FilterChain chain = (req, res) -> chainCalled[0] = true;

        filter.doFilter(request, response, chain);

        // The status endpoint passes straight through without authentication or metrics.
        assertEquals(true, chainCalled[0]);
        assertEquals(0, meterRegistry.find("philter.api.requests").counters().size());
    }

}
