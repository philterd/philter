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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.UserService;
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
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;
    private static final String UNKNOWN_AUTH_HEADER = "Bearer sk_unknownunknownunknownunknown00";

    @Mock private LedgerDataService ledgerService;
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

        final LedgerApiController controller = new LedgerApiController(
                ledgerService, userService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

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

    private LedgerEntity chainHead(final String documentId, final String filename) {
        final LedgerEntity entity = new LedgerEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setFilename(filename);
        entity.setPreviousHash(LedgerDataService.GENESIS);
        entity.setHash("hash-" + documentId);
        entity.setTimestamp(new Date());
        return entity;
    }

    @Test
    void listScopesToUserAndReturnsChains() throws Exception {
        when(ledgerService.findChainsByUserId(any(), eq(userId), anyInt(), anyInt(), any()))
                .thenReturn(List.of(chainHead("doc-1", "a.txt")));
        when(ledgerService.countChainsByUserId(userId)).thenReturn(1);

        final String body = mockMvc.perform(get("/api/ledger").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-list"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("doc-1"));
        verify(ledgerService).findChainsByUserId(any(), eq(userId), anyInt(), anyInt(), any());
    }

    @Test
    void listWithQueryUsesSearch() throws Exception {
        when(ledgerService.searchChainsByUserId(any(), eq(userId), eq("invoice"), any()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/ledger").header("Authorization", AUTH_HEADER)
                        .param("q", "invoice")
                        .requestAttr("requestId", "req-search"))
                .andExpect(status().isOk());

        verify(ledgerService).searchChainsByUserId(any(), eq(userId), eq("invoice"), any());
        verify(ledgerService, never()).findChainsByUserId(any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void getChainReturnsEntriesAndValidity() throws Exception {
        when(ledgerService.getChain(userId, "doc-1")).thenReturn(List.of(chainHead("doc-1", "a.txt")));
        when(ledgerService.isChainValid(userId, "doc-1")).thenReturn(true);

        final String body = mockMvc.perform(get("/api/ledger/doc-1").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-chain"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"valid\":true"));
    }

    @Test
    void getChainReturns404WhenEmpty() throws Exception {
        when(ledgerService.getChain(userId, "missing")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/ledger/missing").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-chain-404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void validateReturnsValidity() throws Exception {
        when(ledgerService.getChain(userId, "doc-1")).thenReturn(List.of(chainHead("doc-1", "a.txt")));
        when(ledgerService.isChainValid(userId, "doc-1")).thenReturn(false);

        final String body = mockMvc.perform(get("/api/ledger/doc-1/valid").header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"valid\":false"));
    }

    @Test
    void exportReturnsAttachment() throws Exception {
        when(ledgerService.getChain(userId, "doc-1")).thenReturn(List.of(chainHead("doc-1", "a.txt")));

        final var response = mockMvc.perform(get("/api/ledger/doc-1/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export"))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertTrue(response.getHeader("Content-Disposition").contains("ledger-doc-1-export.json"));
        assertTrue(response.getContentAsString().contains("\"version\""));
    }

    @Test
    void exportReturns404WhenEmpty() throws Exception {
        when(ledgerService.getChain(userId, "missing")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/ledger/missing/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export-404"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteChainInvokesService() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/ledger/doc-1")
                        .header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-del"))
                .andExpect(status().isOk());

        verify(ledgerService).deleteByDocumentId(any(), eq(userId), eq("doc-1"), any());
    }

    @Test
    void purgeDeletesOlderThanDays() throws Exception {
        when(ledgerService.deleteChainsByUserIdAndOlderThan(any(), eq(userId), eq(30))).thenReturn(7L);

        final String body = mockMvc.perform(request(HttpMethod.DELETE, "/api/ledger")
                        .header("Authorization", AUTH_HEADER)
                        .param("older_than_days", "30")
                        .requestAttr("requestId", "req-purge"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("Deleted 7"));
        verify(ledgerService).deleteChainsByUserIdAndOlderThan(any(), eq(userId), eq(30));
    }

    @Test
    void purgeRejectsNegativeDays() throws Exception {
        mockMvc.perform(request(HttpMethod.DELETE, "/api/ledger")
                        .header("Authorization", AUTH_HEADER)
                        .param("older_than_days", "-1")
                        .requestAttr("requestId", "req-purge-bad"))
                .andExpect(status().isBadRequest());

        verify(ledgerService, never()).deleteChainsByUserIdAndOlderThan(any(), any(), anyInt());
    }

    @Test
    void rejectsUnknownApiKey() throws Exception {
        mockMvc.perform(get("/api/ledger").header("Authorization", UNKNOWN_AUTH_HEADER)
                        .requestAttr("requestId", "req-401"))
                .andExpect(status().isUnauthorized());

        verify(ledgerService, never()).findChainsByUserId(any(), any(), anyInt(), anyInt(), any());
    }

    // ----- Admin cross-user access via the owner parameter -----

    /** Makes the calling user an admin. */
    private void makeCallerAdmin() {
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
    }

    /** Stubs userService.findByEmail so the email resolves to a user with the given id. */
    private void makeOwnerLookup(final String email, final ObjectId ownerId) {
        final UserEntity owner = new UserEntity();
        owner.setId(ownerId);
        owner.setEmail(email);
        when(userService.findByEmail(email)).thenReturn(owner);
    }

    @Test
    void adminCanGetAnotherUsersChainViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        when(ledgerService.getChain(otherUser, "doc-1")).thenReturn(List.of(chainHead("doc-1", "a.txt")));
        when(ledgerService.isChainValid(otherUser, "doc-1")).thenReturn(true);

        mockMvc.perform(get("/api/ledger/doc-1").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-admin-chain"))
                .andExpect(status().isOk());

        // The admin's request operates on the named owner's ledger, not their own id.
        verify(ledgerService).getChain(otherUser, "doc-1");
    }

    @Test
    void adminCanPurgeAnotherUsersEntriesViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        when(ledgerService.deleteChainsByUserIdAndOlderThan(any(), eq(otherUser), eq(30))).thenReturn(3L);

        mockMvc.perform(request(HttpMethod.DELETE, "/api/ledger")
                        .header("Authorization", AUTH_HEADER)
                        .param("older_than_days", "30")
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-admin-purge"))
                .andExpect(status().isOk());

        verify(ledgerService).deleteChainsByUserIdAndOlderThan(any(), eq(otherUser), eq(30));
    }

    @Test
    void nonAdminNamingAnotherOwnerGets404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        // The caller is not an admin.
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/ledger/doc-1").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-forbidden"))
                .andExpect(status().isNotFound());

        // A non-admin must never reach another user's ledger.
        verify(ledgerService, never()).getChain(eq(otherUser), any());
    }

    // ----- Cross-user isolation: a plain request (no owner) can never reach another user's chain -----
    //
    // The caller asks for a document id whose ledger belongs to a different user. The chain lookup is
    // scoped to the caller's id, so it comes back empty and the endpoint returns 404 — the owning
    // user's id is never queried. A regression that dropped user_id from the chain query would leak
    // another user's redaction ledger (which contains the decrypted tokens) here.

    @Test
    void getChainForAnotherUsersDocumentReturns404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final String documentId = "doc-owned-by-other";
        // Scoped to the caller, the chain is empty even though it exists for otherUser.
        when(ledgerService.getChain(userId, documentId)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/ledger/" + documentId).header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-iso-chain"))
                .andExpect(status().isNotFound());

        verify(ledgerService).getChain(userId, documentId);
        verify(ledgerService, never()).getChain(eq(otherUser), any());
    }

    @Test
    void exportingAnotherUsersChainReturns404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final String documentId = "doc-owned-by-other";
        when(ledgerService.getChain(userId, documentId)).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/ledger/" + documentId + "/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-iso-export"))
                .andExpect(status().isNotFound());

        verify(ledgerService).getChain(userId, documentId);
        verify(ledgerService, never()).getChain(eq(otherUser), any());
    }

}
