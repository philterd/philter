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
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.filtering.AppliedPolicy;
import ai.philterd.philter.services.filtering.RedactionOutcome;
import ai.philterd.philter.services.filtering.RedactionService;
import ai.philterd.philter.services.policies.PolicyNotFoundException;
import ai.philterd.philter.services.signing.SigningService;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FilterApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
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

        // Keyed by the hash, as production keys it, and load-bearing: a stub keyed any other way
        // authenticates nobody and every test in the class fails on a 401.
        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenReturn(apiKeyEntity);

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
        return new RedactionOutcome("doc-filter", result, new AppliedPolicy("default", 4, "abc123hash"));
    }

    @Test
    void textEndpointReturnsRedactedTextForOwningUser() throws Exception {
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
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
        // The id the redaction was recorded under, not a fresh one minted for the response.
        org.junit.jupiter.api.Assertions.assertEquals("doc-filter", response.getHeader("X-Document-Id"),
                "X-Document-Id must be the id the ledger and audit trail were written under");

        // The redaction must be attributed to the owning user id, not the API key's own id.
        verify(redactionService).filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any());
    }

    @Test
    void missingAuthorizationIsUnauthorized() throws Exception {
        when(apiKeyCache.containsApiKey(EncryptionService.hashSha256("nope"))).thenReturn(false);
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
                        .content(validPdf()))
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
        // The policy must exist: an unknown one is now refused at enqueue rather than accepted.
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setName("default");
        policyEntity.setRevision(1);
        policyEntity.setPolicy("{\"identifiers\":{}}");
        when(policyDataService.findOne("default", userId)).thenReturn(policyEntity);

        final String responseBody = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.parseMediaType("application/zip"))
                        .content(validPdf()))
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
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.APPLICATION_PDF), any()))
                .thenReturn(outcome(binaryResult(redacted)));

        final var response = mockMvc.perform(post("/api/filter?async=false")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content(validPdf()))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        org.junit.jupiter.api.Assertions.assertArrayEquals(redacted, response.getContentAsByteArray());
        org.junit.jupiter.api.Assertions.assertNotNull(response.getHeader(FilterApiController.DOCUMENT_ID_HEADER));
        org.junit.jupiter.api.Assertions.assertEquals("default", response.getHeader("X-Philter-Policy-Name"));
        org.junit.jupiter.api.Assertions.assertEquals("4", response.getHeader("X-Philter-Policy-Version"));

        // Synchronous path must filter directly (using the owning user id) and never enqueue.
        verify(redactionService).filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.APPLICATION_PDF), any());
        verify(pendingDocumentDataService, never()).save(any());
    }

    @Test
    void pdfEndpointRefusesSignTrueRatherThanAnsweringUnsigned() throws Exception {
        // Two separate handlers, so each is asserted by name rather than through a bare andExpect.
        for (final MediaType accept : new MediaType[]{MediaType.APPLICATION_PDF, MediaType.valueOf("application/zip")}) {
            final var response = mockMvc.perform(post("/api/filter?sign=true")
                            .header("Authorization", AUTH_HEADER)
                            .contentType(MediaType.APPLICATION_PDF)
                            .accept(accept)
                            .content(validPdf()))
                    .andReturn().getResponse();

            org.junit.jupiter.api.Assertions.assertEquals(400, response.getStatus(),
                    "Accept: " + accept + " must refuse sign=true");
        }

        // Refused before any work is done, so nothing is enqueued or filtered.
        verify(pendingDocumentDataService, never()).save(any());
        verify(redactionService, never()).filter(any(), any(), any(), any(byte[].class), any(), any());
    }

    @Test
    void pdfEndpointAcceptsSignFalseWhichAsksForNothing() throws Exception {
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setName("default");
        policyEntity.setRevision(7);
        policyEntity.setPolicy("{\"identifiers\":{}}");
        when(policyDataService.findOne("default", userId)).thenReturn(policyEntity);
        when(policyVersionDataService.snapshot(policyEntity)).thenReturn("hash7");

        mockMvc.perform(post("/api/filter?sign=false")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content(validPdf()))
                .andExpect(status().isAccepted());
    }

    @Test
    void textEndpointAsksForSigningWhenTheRequestSetsSignTrue() throws Exception {
        when(signingService.shouldSign(anyBoolean())).thenReturn(true);
        when(signingService.sign(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn("mock.jwt.token");
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
                .thenReturn(outcome(textResult("Redacted.")));

        final var response = mockMvc.perform(post("/api/filter?sign=true")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        verify(signingService).shouldSign(true);
        org.junit.jupiter.api.Assertions.assertEquals("mock.jwt.token",
                response.getHeader("X-Philter-Signature"));
    }

    @Test
    void textEndpointDoesNotAskForSigningWhenTheParameterIsAbsent() throws Exception {
        when(signingService.shouldSign(anyBoolean())).thenReturn(false);
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
                .thenReturn(outcome(textResult("Redacted.")));

        mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("Original."))
                .andExpect(status().isOk());

        verify(signingService).shouldSign(false);
    }

    @Test
    void textEndpointIncludesSignatureHeaderWhenSigningEnabled() throws Exception {
        when(signingService.shouldSign(anyBoolean())).thenReturn(true);
        when(signingService.sign(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn("mock.jwt.token");
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
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
        when(signingService.shouldSign(anyBoolean())).thenReturn(false);
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
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
        when(signingService.shouldSign(anyBoolean())).thenReturn(true);
        when(signingService.sign(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenThrow(new RuntimeException("key unavailable"));
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
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
        when(apiKeyCache.containsApiKey(EncryptionService.hashSha256("bad-key"))).thenReturn(false);
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


    @Test
    @org.junit.jupiter.api.DisplayName("An async request naming an unknown policy is a 404")
    void asyncWithAnUnknownPolicyIsRefused() throws Exception {
        when(policyDataService.findOne("default", userId)).thenReturn(null);

        mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content(validPdf()))
                .andExpect(status().isNotFound());

        verify(pendingDocumentDataService, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("A synchronous request naming an unknown policy is a 404, not a server error")
    void syncWithAnUnknownPolicyIsAlsoNotFound() throws Exception {
        when(redactionService.filter(eq("nosuch"), eq(userId), eq(""), any(byte[].class), eq(MimeType.TEXT_PLAIN), any()))
                .thenThrow(new PolicyNotFoundException("The policy 'nosuch' does not exist."));

        mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .param("p", "nosuch")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("anything"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingCustomListReturnsConfigurationErrorWithoutRedactedOutput() throws Exception {
        final String message = "Policy references unavailable custom lists: required-names.";
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class),
                eq(MimeType.TEXT_PLAIN), any()))
                .thenThrow(new ai.philterd.philter.services.policies.PolicyResolutionException(message));
        mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("Sensitive original text"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(message))
                .andExpect(header().doesNotExist("X-Document-Id"));
    }

    @Test
    void synchronousZipContainsTheRedactedPdf() throws Exception {
        byte[] pdf = "%PDF-1.7 redacted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.APPLICATION_PDF), any()))
                .thenReturn(outcome(binaryResult(pdf)));
        var response = mockMvc.perform(post("/api/filter?async=false").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF).accept("application/zip").content(validPdf()))
                .andExpect(status().isOk()).andExpect(header().exists(FilterApiController.DOCUMENT_ID_HEADER))
                .andReturn().getResponse();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            org.junit.jupiter.api.Assertions.assertEquals("redacted.pdf", zip.getNextEntry().getName());
            org.junit.jupiter.api.Assertions.assertArrayEquals(pdf, zip.readAllBytes());
            org.junit.jupiter.api.Assertions.assertNull(zip.getNextEntry());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {429, 503})
    void queueCapacityPreservesStatusAndRetryAfter(int statusCode) throws Exception {
        var policy = new PolicyEntity(); policy.setName("default"); policy.setPolicy("{}");
        when(policyDataService.findOne("default", userId)).thenReturn(policy);
        when(pendingDocumentDataService.save(any())).thenThrow(new ai.philterd.philter.data.services.QueueCapacityException("Queue full.", statusCode));
        mockMvc.perform(post("/api/filter").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF).accept(MediaType.APPLICATION_PDF).content(validPdf()))
                .andExpect(status().is(statusCode)).andExpect(header().string("Retry-After", "5"));
    }

    private static byte[] validPdf() throws Exception {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage()); pdf.save(out); return out.toByteArray();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"application/pdf", "application/zip"})
    void malformedAsyncPdfReturns400BeforeAdmission(String accept) throws Exception {
        mockMvc.perform(post("/api/filter").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF).accept(accept)
                        .content("%PDF-1.7\ninvalid truncated document"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(pendingDocumentDataService, policyVersionDataService);
    }

    @Test
    void downstreamPdfIoFailureRemains500() throws Exception {
        when(redactionService.filter(eq("default"), eq(userId), eq(""), any(byte[].class), eq(MimeType.APPLICATION_PDF), any()))
                .thenThrow(new java.io.IOException("storage unavailable"));
        mockMvc.perform(post("/api/filter").param("async", "false")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF).accept(MediaType.APPLICATION_PDF).content(validPdf()))
                .andExpect(status().isInternalServerError());
    }

}
