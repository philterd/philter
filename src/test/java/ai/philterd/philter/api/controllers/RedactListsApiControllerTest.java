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
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.RedactListsDataService.RedactList;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the always/never redact-lists endpoints scope every read and replace to the owning
 * user id (not the API key's own id), normalize and bound the supplied terms, and honor the admin
 * cross-user {@code owner} parameter.
 */
@ExtendWith(MockitoExtension.class)
class RedactListsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private RedactListsDataService redactListsService;
    @Mock private UserService userService;
    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private ai.philterd.philter.services.cache.ApiKeyCache apiKeyCache;

    private ObjectId userId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userId = new ObjectId();
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        // A distinct API-key _id so a regression to getId() instead of getUserId() would fail.
        apiKeyEntity.setId(new ObjectId());

        // Keyed by the hash, as production keys it, and load-bearing: a stub keyed any other way
        // authenticates nobody and every test in the class fails on a 401.
        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenReturn(apiKeyEntity);

        final RedactListsApiController controller = new RedactListsApiController(
                redactListsService, userService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

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

    private RedactListsEntity entityWith(final List<String> always, final List<String> never) {
        final RedactListsEntity entity = new RedactListsEntity();
        entity.setUserId(userId);
        entity.setTermsToAlwaysRedact(always);
        entity.setTermsToNeverRedact(never);
        return entity;
    }

    // ----- GET -----

    @Test
    void getReturnsListsScopedToOwningUserId() throws Exception {
        when(redactListsService.find(userId)).thenReturn(entityWith(List.of("alice"), List.of("acme")));

        final String body = mockMvc.perform(get("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-get"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("alice"));
        assertTrue(body.contains("acme"));
        // The lookup is scoped to the owning user id, never the API key's own id.
        verify(redactListsService).find(userId);
    }

    @Test
    void getReturnsEmptyListsWhenNoneSaved() throws Exception {
        when(redactListsService.find(userId)).thenReturn(null);

        final String body = mockMvc.perform(get("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-get-empty"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // A missing document is an empty pair of lists, not a 404.
        assertTrue(body.contains("\"alwaysRedact\":[]"));
        assertTrue(body.contains("\"neverRedact\":[]"));
    }

    // ----- POST (replace) -----

    @Test
    void postReplacesBothListsScopedToOwningUserId() throws Exception {
        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"alice\",\"bob\"],\"neverRedact\":[\"acme\"]}")
                        .requestAttr("requestId", "req-post"))
                .andExpect(status().isOk());

        verify(redactListsService).saveOrUpdate(eq("req-post"), eq(userId),
                eq(List.of("alice", "bob")), eq(List.of("acme")), anyString());
    }

    @Test
    void postTrimsAndDropsBlankTerms() throws Exception {
        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"  alice  \",\"\",\"   \",\"bob\"],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-trim"))
                .andExpect(status().isOk());

        // Whitespace trimmed, empty/blank entries dropped.
        verify(redactListsService).saveOrUpdate(eq("req-trim"), eq(userId),
                eq(List.of("alice", "bob")), eq(List.of()), anyString());
    }

    @Test
    void postTreatsOmittedListAsEmpty() throws Exception {
        // Only alwaysRedact supplied; neverRedact is omitted and must be cleared (empty), since a POST
        // replaces the whole resource.
        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"alice\"]}")
                        .requestAttr("requestId", "req-omit"))
                .andExpect(status().isOk());

        verify(redactListsService).saveOrUpdate(eq("req-omit"), eq(userId),
                eq(List.of("alice")), eq(List.of()), anyString());
    }

    @Test
    void postRejectsTooManyTerms() throws Exception {
        final String terms = IntStream.rangeClosed(0, RedactListsDataService.MAXIMUM_TERMS_PER_LIST)
                .mapToObj(i -> "\"t" + i + "\"")
                .collect(Collectors.joining(","));

        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[" + terms + "],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-too-many"))
                .andExpect(status().isBadRequest());

        verify(redactListsService, never()).saveOrUpdate(anyString(), any(), any(), any(), anyString());
    }

    @Test
    void postRejectsTooLongTerm() throws Exception {
        final String longTerm = "x".repeat(RedactListsDataService.MAXIMUM_TERM_LENGTH + 1);

        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"" + longTerm + "\"],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-too-long"))
                .andExpect(status().isBadRequest());

        verify(redactListsService, never()).saveOrUpdate(anyString(), any(), any(), any(), anyString());
    }

    @Test
    void postRejectsMalformedBody() throws Exception {
        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ not json ")
                        .requestAttr("requestId", "req-bad"))
                .andExpect(status().isBadRequest());

        verify(redactListsService, never()).saveOrUpdate(anyString(), any(), any(), any(), anyString());
    }

    // ----- PUT (append) -----

    @Test
    void putAppendsToExistingListsScopedToOwningUserId() throws Exception {
        when(redactListsService.find(userId)).thenReturn(entityWith(List.of("alice"), List.of("acme")));

        mockMvc.perform(put("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"bob\"],\"neverRedact\":[\"globex\"]}")
                        .requestAttr("requestId", "req-put"))
                .andExpect(status().isOk());

        // Existing terms are preserved and the new terms appended after them.
        verify(redactListsService).saveOrUpdate(eq("req-put"), eq(userId),
                eq(List.of("alice", "bob")), eq(List.of("acme", "globex")), anyString());
    }

    @Test
    void putDoesNotDuplicateTermsAlreadyPresent() throws Exception {
        when(redactListsService.find(userId)).thenReturn(entityWith(List.of("alice", "bob"), List.of()));

        mockMvc.perform(put("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"bob\",\"carol\"],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-dedup"))
                .andExpect(status().isOk());

        // "bob" already present is not added again; only "carol" is appended. Nothing is appended to
        // the never-redact list, so it is not written.
        verify(redactListsService).saveOrUpdate(eq("req-dedup"), eq(userId),
                eq(List.of("alice", "bob", "carol")), isNull(), anyString());
    }

    @Test
    void putLeavesOmittedListUnchanged() throws Exception {
        when(redactListsService.find(userId)).thenReturn(entityWith(List.of("alice"), List.of("acme")));

        // Only alwaysRedact supplied; neverRedact is omitted and must be left as-is (not cleared).
        mockMvc.perform(put("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"bob\"]}")
                        .requestAttr("requestId", "req-put-omit"))
                .andExpect(status().isOk());

        verify(redactListsService).saveOrUpdate(eq("req-put-omit"), eq(userId),
                eq(List.of("alice", "bob")), isNull(), anyString());
    }

    @Test
    void putAppendsWhenNoListsExistYet() throws Exception {
        when(redactListsService.find(userId)).thenReturn(null);

        mockMvc.perform(put("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"alice\"],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-put-new"))
                .andExpect(status().isOk());

        verify(redactListsService).saveOrUpdate(eq("req-put-new"), eq(userId),
                eq(List.of("alice")), isNull(), anyString());
    }

    @Test
    void putRejectsWhenAppendExceedsLimit() throws Exception {
        // Already at the maximum; appending one more must be rejected.
        final List<String> full = IntStream.range(0, RedactListsDataService.MAXIMUM_TERMS_PER_LIST)
                .mapToObj(i -> "t" + i)
                .collect(Collectors.toList());
        when(redactListsService.find(userId)).thenReturn(entityWith(full, List.of()));

        mockMvc.perform(put("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"one-too-many\"],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-put-over"))
                .andExpect(status().isBadRequest());

        verify(redactListsService, never()).saveOrUpdate(anyString(), any(), any(), any(), anyString());
    }

    // ----- Admin cross-user access via owner -----

    @Test
    void adminCanReplaceAnotherUsersListsViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
        final UserEntity owner = new UserEntity();
        owner.setId(otherUser);
        owner.setEmail("other@example.com");
        when(userService.findAnyByUsername("other@example.com")).thenReturn(owner);

        mockMvc.perform(post("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alwaysRedact\":[\"x\"],\"neverRedact\":[]}")
                        .requestAttr("requestId", "req-admin"))
                .andExpect(status().isOk());

        // The replace targets the named owner, not the admin caller's id.
        verify(redactListsService).saveOrUpdate(eq("req-admin"), eq(otherUser), eq(List.of("x")), eq(List.of()), anyString());
    }

    @Test
    void nonAdminNamingAnotherOwnerGets404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final UserEntity owner = new UserEntity();
        owner.setId(otherUser);
        owner.setEmail("other@example.com");
        when(userService.findAnyByUsername("other@example.com")).thenReturn(owner);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-forbidden"))
                .andExpect(status().isNotFound());

        verify(redactListsService, never()).find(otherUser);
    }


    // ----- One list -----

    @Test
    void getReturnsEachListsRevisionWithBothLists() throws Exception {
        final RedactListsEntity entity = entityWith(List.of("alice"), List.of("acme"));
        entity.setAlwaysRedactRevision(4);
        entity.setNeverRedactRevision(2);
        when(redactListsService.find(userId)).thenReturn(entity);

        mockMvc.perform(get("/api/redact-lists").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-get-revisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alwaysRedactRevision").value(4))
                .andExpect(jsonPath("$.neverRedactRevision").value(2));
    }

    @Test
    void getOneListReturnsItsTermsAndRevisionWithAnETag() throws Exception {
        when(redactListsService.findList(userId, RedactList.NEVER))
                .thenReturn(new RedactListsDataService.ListContents(List.of("acme"), 3L));

        mockMvc.perform(get("/api/redact-lists/never").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-get-one"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.terms[0]").value("acme"))
                .andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    void aListOtherThanAlwaysOrNeverIsNotFound() throws Exception {
        mockMvc.perform(get("/api/redact-lists/sometimes").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-get-unknown"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/redact-lists/sometimes").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terms\":[\"x\"]}")
                        .requestAttr("requestId", "req-put-unknown"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(redactListsService);
    }

    @Test
    void replacingOneListSendsTheRevisionFromIfMatch() throws Exception {
        when(redactListsService.replaceList(eq("req-put-one"), eq(userId), eq(RedactList.ALWAYS),
                eq(List.of("bob")), eq(3L), anyString()))
                .thenReturn(new RedactListsDataService.ListContents(List.of("bob"), 4L));

        mockMvc.perform(put("/api/redact-lists/always").header("Authorization", AUTH_HEADER)
                        .header("If-Match", "\"3\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terms\":[\" bob \", \"\"]}")
                        .requestAttr("requestId", "req-put-one"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.revision").value(4));

        verify(redactListsService, never()).saveOrUpdate(anyString(), any(), any(), any(), anyString());
    }

    @Test
    void replacingOneListWithoutIfMatchWritesWhateverItsRevision() throws Exception {
        when(redactListsService.replaceList(eq("req-put-any"), eq(userId), eq(RedactList.NEVER),
                eq(List.of()), isNull(), anyString()))
                .thenReturn(new RedactListsDataService.ListContents(List.of(), 1L));

        mockMvc.perform(put("/api/redact-lists/never").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .requestAttr("requestId", "req-put-any"))
                .andExpect(status().isOk());
    }

    @Test
    void aStaleRevisionIsAConflictWithAReason() throws Exception {
        when(redactListsService.replaceList(anyString(), eq(userId), eq(RedactList.ALWAYS), any(), eq(2L), anyString()))
                .thenReturn(null);

        mockMvc.perform(put("/api/redact-lists/always").header("Authorization", AUTH_HEADER)
                        .header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terms\":[\"x\"]}")
                        .requestAttr("requestId", "req-put-stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("redact_list_changed"));
    }

    @Test
    void anIfMatchThatIsNotARevisionIsRefusedBeforeWriting() throws Exception {
        for (final String ifMatch : List.of("*", "W/\"3\"", "\"-1\"", "abc", "\"1\", \"2\"")) {
            mockMvc.perform(put("/api/redact-lists/always").header("Authorization", AUTH_HEADER)
                            .header("If-Match", ifMatch)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"terms\":[\"x\"]}")
                            .requestAttr("requestId", "req-put-bad-match"))
                    .andExpect(status().isBadRequest());
        }

        verify(redactListsService, never()).replaceList(anyString(), any(), any(), any(), any(), anyString());
    }

    @Test
    void replacingOneListIsBoundedLikeTheOthers() throws Exception {
        final String tooLong = "x".repeat(RedactListsDataService.MAXIMUM_TERM_LENGTH + 1);

        mockMvc.perform(put("/api/redact-lists/always").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"terms\":[\"" + tooLong + "\"]}")
                        .requestAttr("requestId", "req-put-long"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/redact-lists/always").header("Authorization", AUTH_HEADER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")
                        .requestAttr("requestId", "req-put-malformed"))
                .andExpect(status().isBadRequest());

        verify(redactListsService, never()).replaceList(anyString(), any(), any(), any(), any(), anyString());
    }

}
