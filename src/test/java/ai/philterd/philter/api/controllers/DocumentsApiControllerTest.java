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

import ai.philterd.philter.api.exceptions.RestApiExceptions;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import ai.philterd.philter.config.AdminAccessConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the documents endpoints scope every query to the owning user id, not the API key's
 * own id. The test deliberately gives the API key entity a distinct {@code _id} and {@code user_id}
 * so a regression to {@code getId()} would fail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock
    private ApiKeyDataService apiKeyDataService;

    @Mock
    private ApiKeyCache apiKeyCache;

    @Mock
    private PendingDocumentDataService pendingDocumentDataService;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    @Mock
    private UserService userService;

    private ObjectId userId;
    private ObjectId apiKeyId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userId = new ObjectId();
        apiKeyId = new ObjectId();

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setId(apiKeyId);

        when(apiKeyCache.containsApiKey(API_KEY)).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey(API_KEY)).thenReturn(apiKeyEntity);

        final DocumentsApiController controller = new DocumentsApiController(
                apiKeyDataService, apiKeyCache, pendingDocumentDataService, userService, auditEventPublisher, new Gson());

        // Admin cross-user access is opt-in (off by default); enable it for the admin tests here.

        AdminAccessConfig.setOverrideForTesting(true);


        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }


        @AfterEach

        void clearAdminAccessOverride() {

            AdminAccessConfig.setOverrideForTesting(null);

        }

    @Test
    void listDocumentsQueriesByOwningUserId() throws Exception {
        when(pendingDocumentDataService.findAllByUserId(eq(userId), eq(0), eq(25)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/documents").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).findAllByUserId(eq(userId), eq(0), eq(25));
    }

    @Test
    void getStatusQueriesByOwningUserId() throws Exception {
        final String documentId = "doc-1";
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setDocumentId(documentId);
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        when(pendingDocumentDataService.findOneByDocumentIdAndUserId(documentId, userId)).thenReturn(entity);

        mockMvc.perform(get("/api/documents/" + documentId + "/status").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).findOneByDocumentIdAndUserId(documentId, userId);
    }

    @Test
    void downloadQueriesByOwningUserId() throws Exception {
        final String documentId = "doc-2";
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setDocumentId(documentId);
        entity.setStatus(PendingDocumentEntity.STATUS_COMPLETE);
        entity.setOutputMimeType("application/pdf");
        entity.setOutput("redacted".getBytes());
        when(pendingDocumentDataService.findOneByDocumentIdAndUserId(documentId, userId)).thenReturn(entity);

        mockMvc.perform(get("/api/documents/" + documentId).header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).findOneByDocumentIdAndUserId(documentId, userId);
        // The download is audited against the owning user, not the API key id.
        verify(auditEventPublisher).auditEvent(eq(documentId), eq(AuditLogEvent.REDACTED_FILE_DOWNLOAD),
                eq(userId), eq(null), eq(null), contains(documentId));
    }

    @Test
    void deleteQueriesByOwningUserId() throws Exception {
        final String documentId = "doc-3";
        when(pendingDocumentDataService.deleteByDocumentIdAndUserId(documentId, userId)).thenReturn(1L);

        mockMvc.perform(delete("/api/documents/" + documentId).header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).deleteByDocumentIdAndUserId(documentId, userId);
        verify(auditEventPublisher).auditEvent(eq(documentId), eq(AuditLogEvent.REDACTED_FILE_DELETED),
                eq(userId), eq(null), eq(null), contains(documentId));
    }

    // ----- Admin cross-user access via the owner parameter -----

    @Test
    void adminCanListAnotherUsersDocumentsViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
        final UserEntity owner = new UserEntity();
        owner.setId(otherUser);
        owner.setEmail("other@example.com");
        when(userService.findByEmail("other@example.com")).thenReturn(owner);
        when(pendingDocumentDataService.findAllByUserId(eq(otherUser), eq(0), eq(25)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/documents").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).findAllByUserId(eq(otherUser), eq(0), eq(25));
    }

    @Test
    void nonAdminNamingAnotherOwnerGets404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final UserEntity owner = new UserEntity();
        owner.setId(otherUser);
        owner.setEmail("other@example.com");
        when(userService.findByEmail("other@example.com")).thenReturn(owner);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/documents").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

}
