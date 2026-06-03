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
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.policies.PolicyValidation;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the policies endpoints scope every query to the owning user id (getUserId), not the
 * API key's own id (getId). The API key entity is given a distinct _id and user_id so a regression
 * would fail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PoliciesApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private PolicyDataService policyDataService;
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

        final PoliciesApiController controller = new PoliciesApiController(
                policyDataService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @Test
    void listScopesToOwningUserId() throws Exception {
        when(policyDataService.findAll(eq(userId), anyInt(), anyInt(), eq(false)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/policies").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(policyDataService).findAll(eq(userId), anyInt(), anyInt(), eq(false));
    }

    @Test
    void deleteScopesToOwningUserId() throws Exception {
        mockMvc.perform(delete("/api/policies/my-policy").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(policyDataService).deleteByName(anyString(),
                eq("my-policy"), eq(userId), eq(Source.API));
    }

    @Test
    void compileValidPhiSqlReturnsCompiledNativePolicy() throws Exception {
        // The controller compiles with the real PhiSQL compiler; the compiled JSON is validated via the
        // (mocked) policy data service, so stub validation to pass.
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));

        final String phiSql = "POLICY ssn_only;\nREDACT SSN WITH MASK;";

        final String body = mockMvc.perform(post("/api/policies/compile")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(phiSql))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"name\":\"ssn_only\""), "response should carry the POLICY name");
        assertTrue(body.contains("ssnFilterStrategies"), "response should carry the native ssn filter");
        assertTrue(body.contains("MASK"), "response should carry the compiled MASK strategy");

        // The compiled native policy is validated before being returned.
        verify(policyDataService).validatePolicy(anyString());
    }

    @Test
    void compileInvalidPhiSqlReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/policies/compile")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("this is not valid phisql"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void compileRejectsUnknownApiKey() throws Exception {
        mockMvc.perform(post("/api/policies/compile")
                        .header("Authorization", "Bearer sk_unknownunknownunknownunknown00")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("POLICY ssn_only;\nREDACT SSN WITH MASK;"))
                .andExpect(status().isUnauthorized());
    }

}
