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
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the contexts endpoints scope queries to the owning user id (getUserId), not the API
 * key's own id (getId).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private ContextDataService contextService;
    @Mock private ContextEntryDataService contextEntryService;
    @Mock private PendingDocumentDataService pendingDocumentDataService;
    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private ApiKeyCache apiKeyCache;

    private ObjectId userId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userId = new ObjectId();
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setId(new ObjectId());

        when(apiKeyCache.containsApiKey(API_KEY)).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey(API_KEY)).thenReturn(apiKeyEntity);

        final ContextsApiController controller = new ContextsApiController(
                contextService, contextEntryService, pendingDocumentDataService,
                apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @Test
    void listScopesToOwningUserId() throws Exception {
        when(contextService.findAll(eq(userId))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/contexts").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-1"))
                .andExpect(status().isOk());

        verify(contextService).findAll(eq(userId));
    }

    @Test
    void createScopesToOwningUserId() throws Exception {
        when(contextService.create(eq("ctx"), eq(userId), anyBoolean(), anyBoolean()))
                .thenReturn(ServiceResponse.success("Context created."));

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts")
                        .header("Authorization", AUTH_HEADER)
                        .param("name", "ctx")
                        .requestAttr("requestId", "req-2"))
                .andExpect(status().isOk());

        verify(contextService).create(eq("ctx"), eq(userId), anyBoolean(), anyBoolean());
    }

    @Test
    void deleteScopesToOwningUserId() throws Exception {
        when(pendingDocumentDataService.hasOpenJobsForContext(eq(userId), eq("ctx"))).thenReturn(false);
        when(contextService.deleteByName(eq("ctx"), eq(userId)))
                .thenReturn(ServiceResponse.success("Context deleted."));

        mockMvc.perform(request(HttpMethod.DELETE, "/api/contexts/ctx")
                        .header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-3"))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).hasOpenJobsForContext(eq(userId), eq("ctx"));
        verify(contextService).deleteByName(eq("ctx"), eq(userId));
    }

}
