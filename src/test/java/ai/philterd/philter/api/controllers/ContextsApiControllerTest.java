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
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.model.ServiceResponse;
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
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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
    private static final String UNKNOWN_AUTH_HEADER = "Bearer sk_unknownunknownunknownunknown00";
    private static final String VALID_HASH = "a".repeat(64);

    @Mock private ContextDataService contextService;
    @Mock private ContextEntryDataService contextEntryService;
    @Mock private PendingDocumentDataService pendingDocumentDataService;
    @Mock private ai.philterd.philter.data.services.UserService userService;
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
                contextService, contextEntryService, pendingDocumentDataService, userService,
                apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

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
        when(contextService.findAll(eq(userId), eq(0), eq(25))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/contexts").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-1"))
                .andExpect(status().isOk());

        // Defaults to the first page (offset 0, limit 25), scoped to the owning user id.
        verify(contextService).findAll(eq(userId), eq(0), eq(25));
    }

    @Test
    void listAppliesPagingParameters() throws Exception {
        when(contextService.findAll(eq(userId), eq(50), eq(10))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/contexts").header("Authorization", AUTH_HEADER)
                        .param("offset", "50")
                        .param("limit", "10")
                        .requestAttr("requestId", "req-paging"))
                .andExpect(status().isOk());

        verify(contextService).findAll(eq(userId), eq(50), eq(10));
    }

    @Test
    void listAllowedForAdminOnAnotherUserViaOwnerParam() throws Exception {
        final ObjectId otherOwner = new ObjectId();
        makeUserAdmin();
        makeOwnerLookup("other@example.com", otherOwner);
        when(contextService.findAll(eq(otherOwner), eq(0), eq(25))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/contexts").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-list-admin"))
                .andExpect(status().isOk());

        // The list is scoped to the named owner, not the admin caller, and the cross-user access is audited.
        verify(contextService).findAll(eq(otherOwner), eq(0), eq(25));
        verify(auditEventPublisher).auditEvent(eq("req-list-admin"), eq(AuditLogEvent.ADMIN_CROSS_USER_ACCESS),
                eq(userId), eq(otherOwner), isNull(), eq("action: list contexts"));
    }

    @Test
    void listForbiddenForNonAdminNamingAnotherOwner() throws Exception {
        final ObjectId otherOwner = new ObjectId();
        makeOwnerLookup("other@example.com", otherOwner);
        when(userService.findOneById(userId)).thenReturn(null); // caller is not an admin

        mockMvc.perform(get("/api/contexts").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-list-forbidden"))
                .andExpect(status().isNotFound());

        verify(contextService, never()).findAll(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
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
    void createReturns409ForDuplicateName() throws Exception {
        // A name the caller already uses yields a 409 ServiceResponse, which must surface as HTTP 409.
        when(contextService.create(eq("dup"), eq(userId), anyBoolean(), anyBoolean()))
                .thenReturn(new ServiceResponse("Context already exists.", false, 409));

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts")
                        .header("Authorization", AUTH_HEADER)
                        .param("name", "dup")
                        .requestAttr("requestId", "req-dup"))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteScopesToOwningUserId() throws Exception {
        when(pendingDocumentDataService.hasOpenJobsForContext(eq(userId), eq("ctx"))).thenReturn(false);
        when(contextService.deleteByName(eq("ctx"), eq(userId), anyBoolean()))
                .thenReturn(ServiceResponse.success("Context deleted."));

        mockMvc.perform(request(HttpMethod.DELETE, "/api/contexts/ctx")
                        .header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-3"))
                .andExpect(status().isOk());

        verify(pendingDocumentDataService).hasOpenJobsForContext(eq(userId), eq("ctx"));
        verify(contextService).deleteByName(eq("ctx"), eq(userId), anyBoolean());
    }

    @Test
    void deleteForwardsAdminFlagFromUserRole() throws Exception {
        final ai.philterd.philter.data.entities.UserEntity adminUser = new ai.philterd.philter.data.entities.UserEntity();
        adminUser.setId(userId);
        adminUser.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(adminUser);
        when(pendingDocumentDataService.hasOpenJobsForContext(eq(userId), eq("ctx"))).thenReturn(false);
        when(contextService.deleteByName(eq("ctx"), eq(userId), eq(true)))
                .thenReturn(ServiceResponse.success("Context deleted."));

        mockMvc.perform(request(HttpMethod.DELETE, "/api/contexts/ctx")
                        .header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-4"))
                .andExpect(status().isOk());

        // An admin caller must have the admin flag forwarded to the service.
        verify(contextService).deleteByName(eq("ctx"), eq(userId), eq(true));
    }

    private static ContextEntity contextOwnedBy(final ObjectId owner) {
        final ContextEntity context = new ContextEntity();
        context.setUserId(owner);
        return context;
    }

    private void makeUserAdmin() {
        final ai.philterd.philter.data.entities.UserEntity adminUser = new ai.philterd.philter.data.entities.UserEntity();
        adminUser.setId(userId);
        adminUser.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(adminUser);
    }

    /** Stubs userService.findByEmail so the given email resolves to a user with the given id. */
    private void makeOwnerLookup(final String email, final ObjectId ownerId) {
        final ai.philterd.philter.data.entities.UserEntity owner = new ai.philterd.philter.data.entities.UserEntity();
        owner.setId(ownerId);
        owner.setEmail(email);
        when(userService.findByEmail(email)).thenReturn(owner);
    }

    // ----- Export -----

    @Test
    void exportScopesToOwningUserIdAndReturnsHashesOnly() throws Exception {
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));

        final ContextEntryEntity entry = new ContextEntryEntity();
        entry.setTokenHash(VALID_HASH);
        entry.setReplacement("{{{REDACTED-person}}}");
        entry.setFilterType("PERSON");
        entry.setReplacementUuid(false);
        when(contextEntryService.findAllByUserIdAndContext(eq(userId), eq("ctx")))
                .thenReturn(List.of(entry));

        final String responseBody = mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.junit.jupiter.api.Assertions.assertTrue(responseBody.contains(VALID_HASH));
        org.junit.jupiter.api.Assertions.assertTrue(responseBody.contains("{{{REDACTED-person}}}"));

        // The export must be scoped to the owning user id, never the API key's own id.
        verify(contextEntryService).findAllByUserIdAndContext(eq(userId), eq("ctx"));
    }

    @Test
    void exportReturns404WhenContextMissing() throws Exception {
        when(contextService.findOne(eq("missing"), eq(userId))).thenReturn(null);

        mockMvc.perform(get("/api/contexts/missing/entries/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export-404"))
                .andExpect(status().isNotFound());

        verify(contextEntryService, never()).findAllByUserIdAndContext(any(), any());
    }

    @Test
    void exportRejectsUnknownApiKey() throws Exception {
        mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", UNKNOWN_AUTH_HEADER)
                        .requestAttr("requestId", "req-export-401"))
                .andExpect(status().isUnauthorized());

        verify(contextEntryService, never()).findAllByUserIdAndContext(any(), any());
    }

    @Test
    void exportAllowedForAdminOnAnotherUsersContextViaOwnerParam() throws Exception {
        final ObjectId otherOwner = new ObjectId();
        // The caller does not own the context, but is an admin and names the owner via the owner param.
        makeUserAdmin();
        makeOwnerLookup("other@example.com", otherOwner);
        when(contextService.findOne(eq("ctx"), eq(otherOwner))).thenReturn(contextOwnedBy(otherOwner));
        when(contextEntryService.findAllByUserIdAndContext(eq(otherOwner), eq("ctx")))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-export-admin"))
                .andExpect(status().isOk());

        // The export operates on the named owner's entries, not the admin caller's id.
        verify(contextEntryService).findAllByUserIdAndContext(eq(otherOwner), eq("ctx"));
    }

    @Test
    void exportForbiddenForNonAdminNamingAnotherOwner() throws Exception {
        // A non-admin caller naming another user as owner must be denied (404), never reaching that
        // user's context.
        final ObjectId otherOwner = new ObjectId();
        makeOwnerLookup("other@example.com", otherOwner);
        when(userService.findOneById(userId)).thenReturn(null); // caller is not an admin

        mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-export-forbidden"))
                .andExpect(status().isNotFound());

        verify(contextEntryService, never()).findAllByUserIdAndContext(any(), any());
    }

    @Test
    void exportForbiddenForNonCreatorNonAdmin() throws Exception {
        // Caller names no owner and is not the creator (owner-scoped lookup misses).
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export-forbidden-self"))
                .andExpect(status().isNotFound());

        verify(contextEntryService, never()).findAllByUserIdAndContext(any(), any());
    }

    // ----- Import -----

    private static final String IMPORT_BODY =
            "{\"version\":1,\"context\":\"ctx\",\"entries\":[" +
            "{\"tokenHash\":\"" + VALID_HASH + "\",\"replacement\":\"R\",\"filterType\":\"PERSON\",\"replacementUuid\":false}]}";

    @Test
    void importScopesToOwningUserIdAndSkipsByDefault() throws Exception {
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));
        when(contextEntryService.importEntryByHash(eq(userId), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(false)))
                .thenReturn(ContextEntryDataService.ImportOutcome.INSERTED);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import"))
                .andExpect(status().isOk());

        // Default conflict policy is skip (overwrite = false), scoped to the owning user id.
        verify(contextEntryService).importEntryByHash(eq(userId), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(false));
    }

    @Test
    void importOverwriteModeForwardsOverwriteFlag() throws Exception {
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));
        when(contextEntryService.importEntryByHash(eq(userId), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(true)))
                .thenReturn(ContextEntryDataService.ImportOutcome.OVERWRITTEN);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .param("on_conflict", "overwrite")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-ow"))
                .andExpect(status().isOk());

        verify(contextEntryService).importEntryByHash(eq(userId), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(true));
    }

    @Test
    void importRejectsInvalidOnConflictValue() throws Exception {
        // Authorized caller, so the request reaches the on_conflict validation.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .param("on_conflict", "bogus")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-bad-conflict"))
                .andExpect(status().isBadRequest());

        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importRejectsPayloadWithoutEntries() throws Exception {
        // Authorized caller, so the request reaches payload validation.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"context\":\"ctx\"}")
                        .requestAttr("requestId", "req-import-no-entries"))
                .andExpect(status().isBadRequest());

        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importRejectsInvalidTokenHash() throws Exception {
        final String badBody = "{\"entries\":[{\"tokenHash\":\"not-a-hash\",\"replacement\":\"R\"}]}";

        // Authorized caller, so the request reaches payload validation.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(badBody)
                        .requestAttr("requestId", "req-import-bad-hash"))
                .andExpect(status().isBadRequest());

        // Validation happens before any write, so nothing is imported.
        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importReturns404WhenContextMissing() throws Exception {
        when(contextService.findOne(eq("missing"), eq(userId))).thenReturn(null);

        final String body =
                "{\"entries\":[{\"tokenHash\":\"" + VALID_HASH + "\",\"replacement\":\"R\",\"filterType\":\"PERSON\"}]}";

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/missing/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body)
                        .requestAttr("requestId", "req-import-404"))
                .andExpect(status().isNotFound());

        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importRejectsUnknownApiKey() throws Exception {
        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", UNKNOWN_AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-401"))
                .andExpect(status().isUnauthorized());

        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importAllowedForAdminOnAnotherUsersContextViaOwnerParam() throws Exception {
        final ObjectId otherOwner = new ObjectId();
        // The caller does not own the context, but is an admin and names the owner via the owner param.
        makeUserAdmin();
        makeOwnerLookup("other@example.com", otherOwner);
        when(contextService.findOne(eq("ctx"), eq(otherOwner))).thenReturn(contextOwnedBy(otherOwner));
        when(contextEntryService.importEntryByHash(eq(otherOwner), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(false)))
                .thenReturn(ContextEntryDataService.ImportOutcome.INSERTED);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-admin"))
                .andExpect(status().isOk());

        // The import writes to the named owner's entries, not the admin caller's id.
        verify(contextEntryService).importEntryByHash(eq(otherOwner), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(false));
    }

    @Test
    void importForbiddenForNonAdminNamingAnotherOwner() throws Exception {
        // A non-admin caller naming another user as owner must be denied (404) before any write.
        final ObjectId otherOwner = new ObjectId();
        makeOwnerLookup("other@example.com", otherOwner);
        when(userService.findOneById(userId)).thenReturn(null); // caller is not an admin

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-forbidden-owner"))
                .andExpect(status().isNotFound());

        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importForbiddenForNonCreatorNonAdmin() throws Exception {
        // Caller names no owner and is not the creator (owner-scoped lookup misses).
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-forbidden"))
                .andExpect(status().isNotFound());

        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    // ----- Auditing -----

    @Test
    void exportPublishesAuditEventWithDetail() throws Exception {
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));

        final ContextEntryEntity entry = new ContextEntryEntity();
        entry.setTokenHash(VALID_HASH);
        entry.setReplacement("R");
        entry.setFilterType("PERSON");
        when(contextEntryService.findAllByUserIdAndContext(eq(userId), eq("ctx"))).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export-audit"))
                .andExpect(status().isOk());

        verify(auditEventPublisher).auditEvent(eq("req-export-audit"), eq(AuditLogEvent.CONTEXT_ENTRIES_EXPORTED),
                eq(userId), isNull(), any(), eq("context: ctx, owner: " + userId + ", count: 1"));
    }

    @Test
    void importPublishesAuditEventWithDetail() throws Exception {
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(contextOwnedBy(userId));
        when(contextEntryService.importEntryByHash(eq(userId), eq("ctx"), eq(VALID_HASH), eq("R"), eq("PERSON"), eq(false), eq(false)))
                .thenReturn(ContextEntryDataService.ImportOutcome.INSERTED);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-audit"))
                .andExpect(status().isOk());

        verify(auditEventPublisher).auditEvent(eq("req-import-audit"), eq(AuditLogEvent.CONTEXT_ENTRIES_IMPORTED),
                eq(userId), isNull(), any(), eq("context: ctx, owner: " + userId + ", inserted: 1, overwritten: 0, skipped: 0"));
    }

    @Test
    void exportAuditsDeniedAttempt() throws Exception {
        // Non-creator, non-admin: the 404 attempt must still be audited.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        mockMvc.perform(get("/api/contexts/ctx/entries/export").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-export-denied-audit"))
                .andExpect(status().isNotFound());

        verify(auditEventPublisher).auditEvent(eq("req-export-denied-audit"), eq(AuditLogEvent.CONTEXT_ENTRIES_EXPORT_DENIED),
                eq(userId), isNull(), any(), eq("context: ctx"));
    }

    @Test
    void importAuditsDeniedAttempt() throws Exception {
        // Non-creator, non-admin: the 404 attempt must still be audited.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-denied-audit"))
                .andExpect(status().isNotFound());

        verify(auditEventPublisher).auditEvent(eq("req-import-denied-audit"), eq(AuditLogEvent.CONTEXT_ENTRIES_IMPORT_DENIED),
                eq(userId), isNull(), any(), eq("context: ctx"));
    }

    @Test
    void importAuditsDeniedAttemptEvenWhenPayloadIsMalformed() throws Exception {
        // Non-creator, non-admin caller sending a malformed payload. Authorization is checked before
        // payload parsing, so the caller gets a 404 (denied) and the attempt is audited — not a 400 for
        // the bad payload — and nothing is imported.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{not valid json")
                        .requestAttr("requestId", "req-import-denied-malformed"))
                .andExpect(status().isNotFound());

        verify(auditEventPublisher).auditEvent(eq("req-import-denied-malformed"), eq(AuditLogEvent.CONTEXT_ENTRIES_IMPORT_DENIED),
                eq(userId), isNull(), any(), eq("context: ctx"));
        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importAuditsDeniedAttemptWhenPayloadHasAnInvalidEntry() throws Exception {
        // Non-creator, non-admin caller sending well-formed JSON whose entry is invalid (bad token hash).
        // Authorization runs before per-entry validation, so the caller gets a 404 (denied) and the
        // attempt is audited — not a 400 for the bad entry — and nothing is imported.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        final String invalidEntryBody = "{\"entries\":[{\"tokenHash\":\"not-a-hash\",\"replacement\":\"R\"}]}";

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(invalidEntryBody)
                        .requestAttr("requestId", "req-import-denied-invalid-entry"))
                .andExpect(status().isNotFound());

        verify(auditEventPublisher).auditEvent(eq("req-import-denied-invalid-entry"), eq(AuditLogEvent.CONTEXT_ENTRIES_IMPORT_DENIED),
                eq(userId), isNull(), any(), eq("context: ctx"));
        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    void importAuditsDeniedAttemptEvenWithInvalidOnConflict() throws Exception {
        // Non-creator, non-admin caller sending an invalid on_conflict value. Authorization runs before
        // the on_conflict check too, so the caller gets a 404 (denied) and the attempt is audited —
        // not a 400 for the bad on_conflict value.
        when(contextService.findOne(eq("ctx"), eq(userId))).thenReturn(null);
        when(userService.findOneById(userId)).thenReturn(null);

        mockMvc.perform(request(HttpMethod.POST, "/api/contexts/ctx/entries/import")
                        .header("Authorization", AUTH_HEADER)
                        .param("on_conflict", "bogus")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(IMPORT_BODY)
                        .requestAttr("requestId", "req-import-denied-bad-conflict"))
                .andExpect(status().isNotFound());

        verify(auditEventPublisher).auditEvent(eq("req-import-denied-bad-conflict"), eq(AuditLogEvent.CONTEXT_ENTRIES_IMPORT_DENIED),
                eq(userId), isNull(), any(), eq("context: ctx"));
        verify(contextEntryService, never()).importEntryByHash(any(), any(), any(), any(), any(), anyBoolean(), anyBoolean());
    }

}
