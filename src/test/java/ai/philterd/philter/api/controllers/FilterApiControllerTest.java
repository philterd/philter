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
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.filtering.RedactionService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                apiKeyDataService, auditEventPublisher, apiKeyCache, pendingDocumentDataService, new Gson());

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

    @Test
    void textEndpointReturnsRedactedTextForOwningUser() throws Exception {
        when(redactionService.filter(eq("default"), eq(userId), eq("none"), any(byte[].class), eq(MimeType.TEXT_PLAIN)))
                .thenReturn(textResult("My name is {{{REDACTED-person}}}."));

        final String body = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.TEXT_PLAIN)
                        .content("My name is John Smith."))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertEquals("My name is {{{REDACTED-person}}}.", body);

        // The redaction must be attributed to the owning user id, not the API key's own id.
        verify(redactionService).filter(eq("default"), eq(userId), eq("none"), any(byte[].class), eq(MimeType.TEXT_PLAIN));
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
    void pdfToPdfAsyncReturns202WithDocumentIdAndLocation() throws Exception {
        final String responseBody = mockMvc.perform(post("/api/filter")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content("%PDF-1.7 fake".getBytes()))
                .andExpect(status().isAccepted())
                .andExpect(header().exists("Location"))
                .andReturn().getResponse().getContentAsString();

        // The body is a JSON object carrying the generated documentId.
        final String documentId = new Gson().fromJson(responseBody, java.util.Map.class).get("documentId").toString();
        org.junit.jupiter.api.Assertions.assertFalse(documentId.isBlank());

        // The async path enqueues a pending document scoped to the owning user, with APPLICATION_PDF input.
        final ArgumentCaptor<PendingDocumentEntity> captor = ArgumentCaptor.forClass(PendingDocumentEntity.class);
        verify(pendingDocumentDataService).save(captor.capture());

        final PendingDocumentEntity saved = captor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(userId, saved.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals(documentId, saved.getDocumentId());
        org.junit.jupiter.api.Assertions.assertEquals(MimeType.APPLICATION_PDF.name(), saved.getInputMimeType());
        org.junit.jupiter.api.Assertions.assertEquals(MediaType.APPLICATION_PDF_VALUE, saved.getOutputMimeType());
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

        final Object documentId = new Gson().fromJson(responseBody, java.util.Map.class).get("documentId");
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
        when(redactionService.filter(eq("default"), eq(userId), eq("none"), any(byte[].class), eq(MimeType.APPLICATION_PDF)))
                .thenReturn(binaryResult(redacted));

        final byte[] responseBytes = mockMvc.perform(post("/api/filter?async=false")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_PDF)
                        .accept(MediaType.APPLICATION_PDF)
                        .content("%PDF-1.7 fake".getBytes()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        org.junit.jupiter.api.Assertions.assertArrayEquals(redacted, responseBytes);

        // Synchronous path must filter directly (using the owning user id) and never enqueue.
        verify(redactionService).filter(eq("default"), eq(userId), eq("none"), any(byte[].class), eq(MimeType.APPLICATION_PDF));
        org.mockito.Mockito.verify(pendingDocumentDataService, org.mockito.Mockito.never()).save(any());
    }

}
