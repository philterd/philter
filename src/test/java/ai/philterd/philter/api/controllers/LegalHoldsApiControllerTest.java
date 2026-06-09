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
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import org.bson.types.ObjectId;
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
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegalHoldsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH = "Bearer " + API_KEY;

    private static final String SET_HOLD_BODY =
            "{\"reference\":\"LIT-001\",\"scopeType\":\"document_chain\","
                    + "\"scopeValue\":\"doc123\",\"reason\":\"Outside counsel\"}";

    @Mock private LegalHoldDataService legalHoldDataService;
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

        AdminAccessConfig.setOverrideForTesting(true);

        final LegalHoldsApiController controller = new LegalHoldsApiController(
                legalHoldDataService, userService, apiKeyDataService,
                auditEventPublisher, apiKeyCache);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @AfterEach
    void clearAdminOverride() {
        AdminAccessConfig.setOverrideForTesting(null);
    }

    // -------------------------------------------------------------------------
    // POST /api/holds — set a hold
    // -------------------------------------------------------------------------

    @Test
    void setHoldReturns401WithNoAuthHeader() throws Exception {
        mockMvc.perform(post("/api/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_HOLD_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setHoldReturns401WithUnknownApiKey() throws Exception {
        mockMvc.perform(post("/api/holds")
                        .header("Authorization", "Bearer sk_unknown000000000000000000000000")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_HOLD_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void setHoldReturns400WhenReferenceMissing() throws Exception {
        mockMvc.perform(post("/api/holds").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopeType\":\"user\",\"scopeValue\":\"val\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setHoldReturns400WhenScopeTypeMissing() throws Exception {
        mockMvc.perform(post("/api/holds").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"R1\",\"scopeValue\":\"val\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setHoldReturns400WhenScopeValueMissing() throws Exception {
        mockMvc.perform(post("/api/holds").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"R1\",\"scopeType\":\"user\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setHoldReturns409WhenDuplicateReference() throws Exception {
        when(legalHoldDataService.create(anyString(), eq("LIT-001"), anyString(),
                anyString(), any(), eq(userId), eq(userId)))
                .thenReturn(new ServiceResponse("Duplicate.", false, 409));

        mockMvc.perform(post("/api/holds").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_HOLD_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    void setHoldReturns201OnSuccess() throws Exception {
        when(legalHoldDataService.create(anyString(), eq("LIT-001"), eq("document_chain"),
                eq("doc123"), eq("Outside counsel"), eq(userId), eq(userId)))
                .thenReturn(new ServiceResponse("Created.", true, 201));

        final LegalHoldEntity created = holdEntity("LIT-001", "document_chain", "doc123");
        when(legalHoldDataService.findByReference("LIT-001", userId)).thenReturn(created);

        final String body = mockMvc.perform(post("/api/holds").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SET_HOLD_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("LIT-001"));
        assertTrue(body.contains("document_chain"));
    }

    @Test
    void setHoldReturns400WhenServiceRejectsInvalidScopeType() throws Exception {
        when(legalHoldDataService.create(anyString(), anyString(), anyString(),
                anyString(), any(), any(), any()))
                .thenReturn(new ServiceResponse("Invalid scope type.", false, 400));

        mockMvc.perform(post("/api/holds").header("Authorization", AUTH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"R1\",\"scopeType\":\"bad\",\"scopeValue\":\"v\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /api/holds — list holds
    // -------------------------------------------------------------------------

    @Test
    void listHoldsReturns401WithNoAuth() throws Exception {
        mockMvc.perform(get("/api/holds")).andExpect(status().isUnauthorized());
    }

    @Test
    void listHoldsReturnsEmptyArrayWhenNoneExist() throws Exception {
        when(legalHoldDataService.findAllByUserId(eq(userId), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        final String body = mockMvc.perform(get("/api/holds").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("[]", body);
    }

    @Test
    void listHoldsReturnsHoldSummaries() throws Exception {
        when(legalHoldDataService.findAllByUserId(eq(userId), anyInt(), anyInt()))
                .thenReturn(List.of(holdEntity("LIT-001", "document_chain", "doc123")));

        final String body = mockMvc.perform(get("/api/holds").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("LIT-001"));
        assertTrue(body.contains("document_chain"));
    }

    @Test
    void listHoldsReturns404WhenNonAdminSpecifiesOwner() throws Exception {
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);
        when(userService.findByEmail("other@example.com")).thenReturn(userWithId("other@example.com", new ObjectId()));

        mockMvc.perform(get("/api/holds").header("Authorization", AUTH)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listHoldsAdminCanListAnotherUsersHolds() throws Exception {
        final ObjectId otherUserId = new ObjectId();
        makeCallerAdmin();
        when(userService.findByEmail("other@example.com")).thenReturn(userWithId("other@example.com", otherUserId));
        when(legalHoldDataService.findAllByUserId(eq(otherUserId), anyInt(), anyInt()))
                .thenReturn(List.of(holdEntity("LIT-ADM", "user", otherUserId.toHexString())));

        final String body = mockMvc.perform(get("/api/holds").header("Authorization", AUTH)
                        .param("owner", "other@example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("LIT-ADM"));
        verify(legalHoldDataService).findAllByUserId(eq(otherUserId), anyInt(), anyInt());
    }

    // -------------------------------------------------------------------------
    // GET /api/holds/{reference} — get a specific hold
    // -------------------------------------------------------------------------

    @Test
    void getHoldReturns401WithNoAuth() throws Exception {
        mockMvc.perform(get("/api/holds/LIT-001")).andExpect(status().isUnauthorized());
    }

    @Test
    void getHoldReturns404WhenNotFound() throws Exception {
        when(legalHoldDataService.findByReference("MISSING", userId)).thenReturn(null);

        mockMvc.perform(get("/api/holds/MISSING").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHoldReturnsHoldDetails() throws Exception {
        when(legalHoldDataService.findByReference("LIT-001", userId))
                .thenReturn(holdEntity("LIT-001", "document_chain", "doc123"));

        final String body = mockMvc.perform(get("/api/holds/LIT-001").header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("LIT-001"));
        assertTrue(body.contains("doc123"));
    }

    @Test
    void getHoldReturns404ForWrongOwner() throws Exception {
        // Non-admin caller specifying owner that doesn't resolve to them → resolveTargetUserId returns null
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);
        when(userService.findByEmail("other@example.com")).thenReturn(userWithId("other@example.com", new ObjectId()));

        mockMvc.perform(get("/api/holds/LIT-001").header("Authorization", AUTH)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getHoldAdminCanRetrieveAnotherUsersHold() throws Exception {
        final ObjectId otherUserId = new ObjectId();
        makeCallerAdmin();
        when(userService.findByEmail("other@example.com")).thenReturn(userWithId("other@example.com", otherUserId));
        when(legalHoldDataService.findByReference("LIT-001", otherUserId))
                .thenReturn(holdEntity("LIT-001", "user", otherUserId.toHexString()));

        mockMvc.perform(get("/api/holds/LIT-001").header("Authorization", AUTH)
                        .param("owner", "other@example.com"))
                .andExpect(status().isOk());

        verify(legalHoldDataService).findByReference("LIT-001", otherUserId);
    }

    // -------------------------------------------------------------------------
    // DELETE /api/holds/{reference} — release a hold
    // -------------------------------------------------------------------------

    @Test
    void releaseHoldReturns401WithNoAuth() throws Exception {
        mockMvc.perform(delete("/api/holds/LIT-001")).andExpect(status().isUnauthorized());
    }

    @Test
    void releaseHoldReturns404WhenNotFound() throws Exception {
        when(legalHoldDataService.release(anyString(), eq("MISSING"), eq(userId)))
                .thenReturn(new ServiceResponse("Not found.", false, 404));

        mockMvc.perform(delete("/api/holds/MISSING").header("Authorization", AUTH))
                .andExpect(status().isNotFound());
    }

    @Test
    void releaseHoldReturns200OnSuccess() throws Exception {
        when(legalHoldDataService.release(anyString(), eq("LIT-001"), eq(userId)))
                .thenReturn(new ServiceResponse("Released.", true, 200));

        mockMvc.perform(delete("/api/holds/LIT-001").header("Authorization", AUTH))
                .andExpect(status().isOk());
    }

    @Test
    void releaseHoldReturns404ForNonAdminOwnerParam() throws Exception {
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);
        when(userService.findByEmail("other@example.com")).thenReturn(userWithId("other@example.com", new ObjectId()));

        mockMvc.perform(delete("/api/holds/LIT-001").header("Authorization", AUTH)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void releaseHoldAdminCanReleaseAnotherUsersHold() throws Exception {
        final ObjectId otherUserId = new ObjectId();
        makeCallerAdmin();
        when(userService.findByEmail("other@example.com")).thenReturn(userWithId("other@example.com", otherUserId));
        when(legalHoldDataService.release(anyString(), eq("LIT-001"), eq(otherUserId)))
                .thenReturn(new ServiceResponse("Released.", true, 200));

        mockMvc.perform(delete("/api/holds/LIT-001").header("Authorization", AUTH)
                        .param("owner", "other@example.com"))
                .andExpect(status().isOk());

        verify(legalHoldDataService).release(anyString(), eq("LIT-001"), eq(otherUserId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LegalHoldEntity holdEntity(final String reference, final String scopeType,
                                        final String scopeValue) {
        final LegalHoldEntity e = new LegalHoldEntity();
        e.setId(new ObjectId());
        e.setUserId(userId);
        e.setReference(reference);
        e.setScopeType(scopeType);
        e.setScopeValue(scopeValue);
        e.setReason("Test reason");
        e.setSetAt(new Date());
        e.setSetByUserId(userId);
        return e;
    }

    private void makeCallerAdmin() {
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
    }

    private UserEntity userWithId(final String email, final ObjectId id) {
        final UserEntity u = new UserEntity();
        u.setId(id);
        u.setEmail(email);
        return u;
    }
}
