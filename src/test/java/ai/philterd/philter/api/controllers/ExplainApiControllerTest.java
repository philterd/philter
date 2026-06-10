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
package ai.philterd.philter.api.controllers;

import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.api.exceptions.RestApiExceptions;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.filtering.AppliedPolicy;
import ai.philterd.philter.services.filtering.RedactionOutcome;
import ai.philterd.philter.services.filtering.RedactionService;
import ai.philterd.philter.services.signing.SigningService;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that {@code POST /api/explain} returns the full {@link TextFilterResult} shape
 * (filteredText plus the nested explanation), preserving compatibility with the 3.4.0 response that
 * clients such as PhilterScope depend on. Returning only the bare {@code Explanation} would omit
 * {@code filteredText} and the {@code explanation} wrapper.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExplainApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private RedactionService redactionService;
    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private SigningService signingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(new ObjectId());
        apiKeyEntity.setId(new ObjectId());

        when(apiKeyCache.containsApiKey(API_KEY)).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey(API_KEY)).thenReturn(apiKeyEntity);

        final ExplainApiController controller = new ExplainApiController(
                redactionService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson(), signingService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @Test
    void explainReturnsFilteredTextAndNestedExplanation() throws Exception {
        final Span span = Span.make(0, 4, FilterType.PERSON, "none", 1.0, "John", "{{{REDACTED-person}}}",
                "salt", false, true, new String[]{}, 0);
        final TextFilterResult result = new TextFilterResult(
                "{{{REDACTED-person}}} was here", "none", 0,
                new Explanation(List.of(span), Collections.emptyList()), Collections.emptyList(), 5);

        when(redactionService.filter(eq("default"), any(), eq(""), any(), any(), any()))
                .thenReturn(new RedactionOutcome(result, new AppliedPolicy("default", 9, "policyhash")));

        final String body = mockMvc.perform(request(HttpMethod.POST, "/api/explain")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("John was here"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The 3.4.0 contract: filteredText is present and the spans are under a nested "explanation".
        assertTrue(body.contains("\"filteredText\""), "response must include filteredText; was: " + body);
        assertTrue(body.contains("{{{REDACTED-person}}} was here"), "response must include the filtered text value");
        assertTrue(body.contains("\"explanation\""), "spans must be nested under explanation");
        assertTrue(body.contains("\"appliedSpans\""), "response must include appliedSpans");

        // The applied policy name and version are surfaced as top-level fields.
        assertTrue(body.contains("\"policyName\":\"default\""), "response must include policyName; was: " + body);
        assertTrue(body.contains("\"policyVersion\":9"), "response must include policyVersion; was: " + body);
    }

    @Test
    void explainEndpointIncludesSignatureHeaderWhenSigningEnabled() throws Exception {
        when(signingService.isSigningEnabled()).thenReturn(true);
        when(signingService.sign(any(), any(), anyInt(), any())).thenReturn("mock.jwt.token");

        final TextFilterResult result = new TextFilterResult("Redacted.", "none", 0,
                new Explanation(Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), 0L);
        when(redactionService.filter(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RedactionOutcome(result, new AppliedPolicy("default", 1, "hash")));

        final var response = mockMvc.perform(post("/api/explain")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertEquals("mock.jwt.token", response.getHeader("X-Philter-Signature"),
                "signature header must be present when signing is enabled");
    }

    @Test
    void explainEndpointDoesNotIncludeSignatureHeaderWhenSigningDisabled() throws Exception {
        when(signingService.isSigningEnabled()).thenReturn(false);

        final TextFilterResult result = new TextFilterResult("Redacted.", "none", 0,
                new Explanation(Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), 0L);
        when(redactionService.filter(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RedactionOutcome(result, new AppliedPolicy("default", 1, "hash")));

        final var response = mockMvc.perform(post("/api/explain")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertNull(response.getHeader("X-Philter-Signature"),
                "signature header must be absent when signing is disabled");
    }

    @Test
    void explainEndpointReturns500WhenSigningFails() throws Exception {
        when(signingService.isSigningEnabled()).thenReturn(true);
        when(signingService.sign(any(), any(), anyInt(), any()))
                .thenThrow(new RuntimeException("key unavailable"));

        final TextFilterResult result = new TextFilterResult("Redacted.", "none", 0,
                new Explanation(Collections.emptyList(), Collections.emptyList()), Collections.emptyList(), 0L);
        when(redactionService.filter(any(), any(), any(), any(), any(), any()))
                .thenReturn(new RedactionOutcome(result, new AppliedPolicy("default", 1, "hash")));

        mockMvc.perform(post("/api/explain")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void unauthorizedExplainResponseDoesNotIncludeSignatureHeader() throws Exception {
        when(apiKeyCache.containsApiKey("bad-key")).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey("bad-key")).thenReturn(null);

        final var response = mockMvc.perform(post("/api/explain")
                        .header("Authorization", "Bearer bad-key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("anything"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        assertNull(response.getHeader("X-Philter-Signature"),
                "signature header must never appear on unauthorized responses");
    }

}
