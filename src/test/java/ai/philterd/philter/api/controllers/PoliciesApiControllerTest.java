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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.policies.PhiSqlCompileService;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the policies endpoints scope every query to the owning user id (getUserId), not the
 * API key's own id (getId). The API key entity is given a distinct _id and user_id so a regression
 * would fail.
 */
@ExtendWith(MockitoExtension.class)
class PoliciesApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
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

        // Keyed by the hash, as production keys it, and load-bearing: a stub keyed any other way
        // authenticates nobody and every test in the class fails on a 401.
        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenReturn(apiKeyEntity);

        final PoliciesApiController controller = new PoliciesApiController(
                policyDataService, userService, apiKeyDataService, auditEventPublisher, apiKeyCache,
                new PhiSqlCompileService(), new Gson());

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

        mockMvc.perform(get("/api/policies").header("Authorization", AUTH_HEADER).requestAttr("requestId", "req-1"))
                .andExpect(status().isOk());

        verify(policyDataService).findAll(eq(userId), anyInt(), anyInt(), eq(false));
    }

    @Test
    void getReturnsThePolicyScopedToTheOwningUserId() throws Exception {
        final PolicyEntity entity = new PolicyEntity();
        entity.setName("my-policy");
        entity.setUserId(userId);
        entity.setPolicy("{\"name\":\"my-policy\",\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}");
        when(policyDataService.findOneOrManaged("my-policy", userId)).thenReturn(entity);

        final String body = mockMvc.perform(get("/api/policies/my-policy").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("ssnFilterStrategies"), "the policy body must be returned: " + body);
        // The lookup must use the key's owning user id, not the key's own id.
        verify(policyDataService).findOneOrManaged("my-policy", userId);
    }

    @Test
    void getReturns404WhenThePolicyDoesNotExist() throws Exception {
        when(policyDataService.findOneOrManaged("missing", userId)).thenReturn(null);

        mockMvc.perform(get("/api/policies/missing").header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound());
    }

    @Test
    void getReturns404WhenANonAdminNamesAnotherOwner() throws Exception {
        makeOwnerLookup("other@example.com", new ObjectId());
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/policies/my-policy").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());

        // The policy must never be read for a caller that is not entitled to the owner's data.
        verify(policyDataService, never()).findOne(anyString(), any());
    }

    @Test
    void deleteScopesToOwningUserId() throws Exception {
        when(policyDataService.deleteByName(anyString(), eq("my-policy"), eq(userId), eq(Source.API), eq(userId), anyString()))
                .thenReturn(new ServiceResponse("Policy deleted.", true, 200));

        mockMvc.perform(delete("/api/policies/my-policy").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk());

        verify(policyDataService).deleteByName(anyString(),
                eq("my-policy"), eq(userId), eq(Source.API), eq(userId), anyString());
    }

    @Test
    void deleteReturns404WithAMessageWhenThePolicyDoesNotExist() throws Exception {
        when(policyDataService.deleteByName(anyString(), eq("missing"), eq(userId), eq(Source.API), eq(userId), anyString()))
                .thenReturn(new ServiceResponse("Policy does not exist.", false, 404));

        mockMvc.perform(delete("/api/policies/missing").header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy does not exist."));
    }

    @Test
    void deleteReturns409WithAReasonForTheDefaultPolicy() throws Exception {
        when(policyDataService.deleteByName(anyString(), eq("default"), eq(userId), eq(Source.API), eq(userId), anyString()))
                .thenReturn(new ServiceResponse("Cannot delete the default policy.", false, 409,
                        PolicyDataService.REASON_POLICY_DEFAULT));

        // An Accept header that excludes JSON still gets the JSON refusal.
        mockMvc.perform(delete("/api/policies/default").header("Authorization", AUTH_HEADER).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Cannot delete the default policy."))
                .andExpect(jsonPath("$.reason").value("policy_default"));
    }

    @Test
    void deletePassesOnAnyOtherRefusalWithItsMessage() throws Exception {
        when(policyDataService.deleteByName(anyString(), eq("my-policy"), eq(userId), eq(Source.API), eq(userId), anyString()))
                .thenReturn(new ServiceResponse("Something else.", false, 503));

        mockMvc.perform(delete("/api/policies/my-policy").header("Authorization", AUTH_HEADER))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Something else."));
    }

    private static final String VALID_POLICY_BODY =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";

    @Test
    void createValidatesAndStoresTheValidPolicy() throws Exception {
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.create(anyString(), any(), anyString(), isNull(), isNull(), anyString(), anyString()))
                .thenReturn(ServiceResponse.success());

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "my-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isCreated());

        // Validated, then written through create() so the name rules, the version snapshot and the
        // cache eviction all apply.
        verify(policyDataService).validatePolicy(anyString());
        verify(policyDataService).create(anyString(), any(), anyString(), isNull(), isNull(),
                eq("my-policy"), anyString());
        verify(policyDataService, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createEmitsActivationAuditEvent() throws Exception {
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.create(anyString(), any(), anyString(), isNull(), isNull(), anyString(), anyString()))
                .thenReturn(ServiceResponse.success());

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "my-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isCreated());

        verify(auditEventPublisher).auditEvent(
                anyString(),
                eq(AuditLogEvent.POLICY_ACTIVATED),
                eq(userId),
                isNull(),
                isNull(),
                eq("policy: my-policy"));
    }

    @Test
    void createReturns409WithAReasonWhenTheNameWasTakenConcurrently() throws Exception {
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.create(anyString(), any(), anyString(), isNull(), isNull(), anyString(), anyString()))
                .thenReturn(new ServiceResponse("A policy with this name already exists.", false, 409,
                        PolicyDataService.REASON_POLICY_EXISTS));

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "my-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A policy with this name already exists."))
                .andExpect(jsonPath("$.reason").value("policy_exists"));
    }

    @Test
    void replaceReturns409WithAReasonWhenThePolicyChangedConcurrently() throws Exception {
        final PolicyEntity existing = new PolicyEntity();
        existing.setId(new ObjectId());
        when(policyDataService.findOne("my-policy", userId)).thenReturn(existing);
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.update(anyString(), eq(userId), eq(existing.getId()), anyString(), isNull(), isNull(), anyString()))
                .thenReturn(new ServiceResponse("Policy changed concurrently. Reload and retry.", false, 409,
                        PolicyDataService.REASON_POLICY_CHANGED));

        // An Accept header that excludes JSON still gets the JSON refusal.
        mockMvc.perform(put("/api/policies/my-policy").header("Authorization", AUTH_HEADER)
                        .accept(MediaType.TEXT_PLAIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Policy changed concurrently. Reload and retry."))
                .andExpect(jsonPath("$.reason").value("policy_changed"));
    }

    @Test
    void replaceReturnsTheServiceMessageWhenThePolicyWasDeletedMeanwhile() throws Exception {
        final PolicyEntity existing = new PolicyEntity();
        existing.setId(new ObjectId());
        when(policyDataService.findOne("my-policy", userId)).thenReturn(existing);
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.update(anyString(), eq(userId), eq(existing.getId()), anyString(), isNull(), isNull(), anyString()))
                .thenReturn(new ServiceResponse("Policy does not exist.", false, 404));

        mockMvc.perform(put("/api/policies/my-policy").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy does not exist."));
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
    void createWithoutANameReturns400NamingTheParameterAndSavesNothing() throws Exception {
        final String body = mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("name"), "the response should name the missing parameter: " + body);

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
        when(userService.findByUsername(email)).thenReturn(owner);
    }

    @Test
    void adminCanListAnotherUsersPoliciesViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        when(policyDataService.findAll(eq(otherUser), anyInt(), anyInt(), eq(false)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/policies").header("Authorization", AUTH_HEADER).requestAttr("requestId", "req-1")
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

        mockMvc.perform(get("/api/policies").header("Authorization", AUTH_HEADER).requestAttr("requestId", "req-1")
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

    // ----- Create never replaces; replace never creates -----

    @Test
    void createNeverReplacesAnExistingPolicy() throws Exception {
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.create(anyString(), eq(userId), anyString(), isNull(), isNull(), eq("my-policy"), anyString()))
                .thenReturn(new ServiceResponse("A policy with this name already exists.", false, 409,
                        PolicyDataService.REASON_POLICY_EXISTS));

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "my-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("policy_exists"));

        verify(policyDataService, never()).update(any(), any(), any(), any(), any(), any(), any());
        verify(auditEventPublisher, never()).auditEvent(anyString(), eq(AuditLogEvent.POLICY_ACTIVATED), any(), any(), any(), any());
    }

    @Test
    void replaceUpdatesTheExistingPolicyAndRecordsItsActivation() throws Exception {
        final PolicyEntity existing = new PolicyEntity();
        existing.setId(new ObjectId());
        when(policyDataService.findOne("my-policy", userId)).thenReturn(existing);
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.update(anyString(), eq(userId), eq(existing.getId()), anyString(), eq("New."), isNull(), anyString()))
                .thenReturn(new ServiceResponse("The policy was updated.", true, 200));

        mockMvc.perform(put("/api/policies/my-policy").header("Authorization", AUTH_HEADER)
                        .param("description", "New.")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isOk());

        verify(policyDataService, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(auditEventPublisher).auditEvent(anyString(), eq(AuditLogEvent.POLICY_ACTIVATED), eq(userId), isNull(), isNull(),
                eq("policy: my-policy"));
    }

    @Test
    void replaceReturns404WithAMessageAndCreatesNothingWhenThePolicyDoesNotExist() throws Exception {
        when(policyDataService.findOne("missing", userId)).thenReturn(null);
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));

        mockMvc.perform(put("/api/policies/missing").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Policy does not exist."));

        verify(policyDataService, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(policyDataService, never()).update(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replaceRejectsAnInvalidPolicyWith400() throws Exception {
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.invalid("Bad policy."));

        mockMvc.perform(put("/api/policies/my-policy").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifiers\":{}}"))
                .andExpect(status().isBadRequest());

        verify(policyDataService, never()).update(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void adminCanReplaceAnotherUsersPolicyViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        final PolicyEntity existing = new PolicyEntity();
        existing.setId(new ObjectId());
        when(policyDataService.findOne("their-policy", otherUser)).thenReturn(existing);
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.update(anyString(), eq(otherUser), eq(existing.getId()), anyString(), isNull(), isNull(), anyString()))
                .thenReturn(new ServiceResponse("The policy was updated.", true, 200));

        mockMvc.perform(put("/api/policies/their-policy").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isOk());

        verify(policyDataService, never()).findOne("their-policy", userId);
    }

    @Test
    void adminCanCreateAPolicyForAnotherUserViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        when(policyDataService.validatePolicy(anyString())).thenReturn(PolicyValidation.valid("ok"));
        when(policyDataService.create(anyString(), eq(otherUser), anyString(), isNull(), isNull(), eq("their-policy"), anyString()))
                .thenReturn(ServiceResponse.success());

        mockMvc.perform(post("/api/policies").header("Authorization", AUTH_HEADER)
                        .param("name", "their-policy")
                        .param("owner", "other@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_POLICY_BODY))
                .andExpect(status().isCreated());
    }

}
