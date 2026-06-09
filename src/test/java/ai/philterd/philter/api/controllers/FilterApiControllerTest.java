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

import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.Explanation;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.api.exceptions.RestApiExceptions;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FilterApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock
    private RedactionService redactionService;

    @Mock
    private PolicyDataService policyDataService;

    @Mock
    private ApiKeyDataService apiKeyDataService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private ApiKeyCache apiKeyCache;

    @Mock
    private PendingDocumentDataService pendingDocumentDataService;

    @Mock
    private PolicyVersionDataService policyVersionDataService;

    @Mock
    private SigningService signingService;

    private ObjectId userId;
    private ObjectId apiKeyId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userId = new ObjectId();
        apiKeyId = new ObjectId();

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        // The entity's own _id differs from the user id; the controller must use the user id.
        apiKeyEntity.setId(apiKeyId);

        when(apiKeyCache.containsApiKey(API_KEY)).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey(API_KEY)).thenReturn(apiKeyEntity);

        final FilterApiController controller = new FilterApiController(redactionService, policyDataService,
                apiKeyDataService, auditEventPublisher, apiKeyCache, pendingDocumentDataService, new Gson(),
                policyVersionDataService, signingService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    private static TextFilterResult textResult(final String filteredText) {
        return new TextFilterResult(filteredText, "none", 0,
                new Explanation(Collections.emptyList(), Collections.emptyList()),
                Collections.emptyList(), 0L);
    }

    private static BinaryDocumentFilterResult binaryResult(final byte[] document) {
        return new BinaryDocumentFilterResult(document, "none",
                new Explanation(Collections.emptyList(), Collections.emptyList()), 0L,
                Collections.emptyList());
    }

    private static RedactionOutcome outcome(final ai.philterd.phileas.model.filtering.AbstractFilterResult result) {
        return new RedactionOutcome(result, new AppliedPolicy("default", 4, "abc123hash"));
    }

    @Test
    void textEndpointReturnsRedactedTextForOwningUser() throws Exception {
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN)))
                .thenReturn(outcome(textResult("My name is {{{REDACTED-person}}}.")));

        final var response = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("My name is John Smith."))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        final String body = response.getContentAsString();
        org.junit.jupiter.api.Assertions.assertEquals("My name is {{{REDACTED-person}}}.", body);

        // The applied policy name, version, and document ID are reported as response headers.
        org.junit.jupiter.api.Assertions.assertEquals("default", response.getHeader("X-Philter-Policy-Name"));
        org.junit.jupiter.api.Assertions.assertEquals("4", response.getHeader("X-Philter-Policy-Version"));
        org.junit.jupiter.api.Assertions.assertNotNull(response.getHeader("X-Document-Id"),
                "every text response must include X-Document-Id");

        // The redaction must be attributed to the owning user id, not the API key's own id.
        verify(redactionService).filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN));
    }

    @Test
    void missingAuthorizationIsUnauthorized() throws Exception {
        when(apiKeyCache.containsApiKey("nope")).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey("nope")).thenReturn(null);

        mockMvc.perform(post("/api/filter")
                        .header("Authorization", "Bearer nope")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("anything"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pdfToPdfAsyncReturns202WithDocumentIdAndLocationAndPinnedPolicyVersion() throws Exception {
        // At enqueue the controller resolves and pins the current policy version, retaining a snapshot.
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setName("default");
        policyEntity.setRevision(7);
        policyEntity.setPolicy("{\"identifiers\":{}}");
        when(policyDataService.findOne("default", userId)).thenReturn(policyEntity);
        when(policyVersionDataService.snapshot(policyEntity)).thenReturn("hash7");

        final var response = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content("%PDF-1.7 fake".getBytes()))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse();

        // The 202 reports the pinned policy name and version.
        org.junit.jupiter.api.Assertions.assertEquals("default", response.getHeader("X-Philter-Policy-Name"));
        org.junit.jupiter.api.Assertions.assertEquals("7", response.getHeader("X-Philter-Policy-Version"));

        // The body is a JSON object carrying the generated documentId.
        final String responseBody = response.getContentAsString();
        final String documentId = new Gson().fromJson(responseBody, Map.class).get("documentId").toString();
        org.junit.jupiter.api.Assertions.assertFalse(documentId.isBlank());

        // The async path enqueues a pending document scoped to the owning user, with APPLICATION_PDF input,
        // and the pinned policy version and content hash.
        final ArgumentCaptor<PendingDocumentEntity> captor = ArgumentCaptor.forClass(PendingDocumentEntity.class);
        verify(pendingDocumentDataService).save(captor.capture());

        final PendingDocumentEntity saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(userId, saved.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(documentId, saved.getDocumentId());
        org.junit.jupiter.api.Assertions.assertEquals(MimeType.APPLICATION_PDF.name(), saved.getInputMimeType());
        org.junit.jupiter.api.Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, saved.getOutputMimeType());
        org.junit.jupiter.api.Assertions.assertEquals(7, saved.getPolicyVersion());
        org.junit.jupiter.api.Assertions.assertEquals("hash7", saved.getPolicyContentHash());
    }

    @Test
    void pdfToZipAsyncEnqueuesZipOutputWithPdfInput() throws Exception {
        final String responseBody = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.parseMediaType("application/zip"))
                        .content("%PDF-1.7 fake".getBytes()))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();

        final Object documentId = new Gson().fromJson(responseBody, Map.class).get("documentId");
        org.junit.jupiter.api.Assertions.assertNotNull(documentId);

        final ArgumentCaptor<PendingDocumentEntity> captor = ArgumentCaptor.forClass(PendingDocumentEntity.class);
        verify(pendingDocumentDataService).save(captor.capture());

        final PendingDocumentEntity saved = captor.getValue();
        // Input MIME type is APPLICATION_PDF; output is the ZIP archive type.
        org.junit.jupiter.api.Assertions.assertEquals(MimeType.APPLICATION_PDF.name(), saved.getInputMimeType());
        org.junit.jupiter.api.Assertions.assertEquals("application/zip", saved.getOutputMimeType());
        org.junit.jupiter.api.Assertions.assertEquals(userId, saved.getUserId());
    }

    @Test
    void pdfSyncReturnsBytesAndDoesNotEnqueue() throws Exception {
        final byte[] redacted = "redacted-pdf-bytes".getBytes();
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.APPLICATION_PDF)))
                .thenReturn(outcome(binaryResult(redacted)));

        final var response = mockMvc.perform(post("/api/filter?async=false")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content("%PDF-1.7 fake".getBytes()))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertArrayEquals(redacted, response.getContentAsByteArray());
        org.junit.jupiter.api.Assertions.assertEquals("default", response.getHeader("X-Philter-Policy-Name"));
        org.junit.jupiter.api.Assertions.assertEquals("4", response.getHeader("X-Philter-Policy-Version"));

        // Synchronous path must filter directly (using the owning user id) and never enqueue.
        verify(redactionService).filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.APPLICATION_PDF));
        verify(pendingDocumentDataService, never()).save(any());
    }

    @Test
    void textEndpointIncludesSignatureHeaderWhenSigningEnabled() throws Exception {
        when(signingService.isSigningEnabled()).thenReturn(true);
        when(signingService.sign(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn("mock.jwt.token");
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN)))
                .thenReturn(outcome(textResult("Redacted.")));

        final var response = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertEquals("mock.jwt.token",
                response.getHeader("X-Philter-Signature"),
                "signature header must be present when signing is enabled");
    }

    @Test
    void textEndpointDoesNotIncludeSignatureHeaderWhenSigningDisabled() throws Exception {
        when(signingService.isSigningEnabled()).thenReturn(false);
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN)))
                .thenReturn(outcome(textResult("Redacted.")));

        final var response = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertNull(response.getHeader("X-Philter-Signature"),
                "signature header must be absent when signing is disabled");
    }

    @Test
    void textEndpointReturns500WhenSigningFails() throws Exception {
        when(signingService.isSigningEnabled()).thenReturn(true);
        when(signingService.sign(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new RuntimeException("key unavailable"));
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN)))
                .thenReturn(outcome(textResult("Redacted.")));

        mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void unauthorizedResponseDoesNotIncludeSignatureHeader() throws Exception {
        when(apiKeyCache.containsApiKey("bad-key")).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey("bad-key")).thenReturn(null);

        final var response = mockMvc.perform(post("/api/filter")
                        .header("Authorization", "Bearer bad-key")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("anything"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertNull(response.getHeader("X-Philter-Signature"),
                "signature header must never appear on error responses");
    }

}
