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
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.policies.PolicyValidation;
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
    @Mock private UserService userService;
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
                policyDataService, userService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

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

    private static final String VALID_POLICY_BODY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Test
    void createValidatesAndStoresTheValidPolicy() throws Exception {
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "my-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isCreated());

        // The policy is validated before being persisted.
        verify(policyDataService).validatePolicy(anyString());
        verify(policyDataService).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createRejectsAnInvalidPolicyWith400AndDoesNotStoreIt() throws Exception {
        when(policyDataService.validatePolicy(anyString()))
                .thenReturn(PolicyValidation.invalid("The policy must contain an 'identifiers' object describing the information to redact."));

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "my-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifiers\":{}}"))
                .andExpect(status().isBadRequest());

        // An invalid policy is never persisted.
        verify(policyDataService, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createRejectsABlankNameWith400BeforeValidating() throws Exception {
        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isBadRequest());

        verify(policyDataService, org.mockito.Mockito.never()).validatePolicy(anyString());
        verify(policyDataService, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
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

    // ----- Admin cross-user access via the owner parameter -----

    private void makeCallerAdmin() {
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
    }

    private void makeOwnerLookup(final String email, final ObjectId ownerId) {
        final UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        owner.setEmail(email);
        when(userService.findByEmail(email)).thenReturn(owner);
    }

    @Test
    void adminCanListAnotherUsersPoliciesViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        when(policyDataService.findAll(eq(otherUser), anyInt(), anyInt(), eq(false)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isOk());

        verify(policyDataService).findAll(eq(otherUser), anyInt(), anyInt(), eq(false));
    }

    @Test
    void nonAdminNamingAnotherOwnerGets404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

}
