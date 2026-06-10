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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
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

import java.util.Date;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyVersionsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;
    private static final String POLICY_NAME = "test-policy";

    private static final String POLICY_JSON_V1 =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    private static final String POLICY_JSON_V2 =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"MASK\"}]}}}";

    @Mock private PolicyDataService policyDataService;
    @Mock private PolicyVersionDataService policyVersionDataService;
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

        final PolicyVersionsApiController controller = new PolicyVersionsApiController(
                policyDataService, policyVersionDataService, userService,
                apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @AfterEach
    void clearAdminAccessOverride() {
        AdminAccessConfig.setOverrideForTesting(null);
    }

    // ----- helpers -----

    private PolicyVersionEntity version(final int revision, final String policyJson) {
        final PolicyVersionEntity v = new PolicyVersionEntity();
        v.setName(POLICY_NAME);
        v.setRevision(revision);
        v.setPolicy(policyJson);
        v.setCapturedTimestamp(new Date());
        v.setContentHash("hash-" + revision);
        v.setUserId(userId);
        return v;
    }

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

    // ============================================================
    // GET /api/policies/{policyName}/versions
    // ============================================================

    @Test
    void listVersionsReturns401WithNoAuthHeader() throws Exception {
        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listVersionsReturns401ForUnknownApiKey() throws Exception {
        when(apiKeyDataService.findOneByApiKey("sk_unknown")).thenReturn(null);

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions")
                        .header("Authorization", "Bearer sk_unknown"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listVersionsReturnsEmptyListWhenNoVersionsExist() throws Exception {
        when(policyVersionDataService.findAllByName(eq(POLICY_NAME), eq(userId), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("[]", body);
    }

    @Test
    void listVersionsReturnsSummaryForEachVersion() throws Exception {
        when(policyVersionDataService.findAllByName(eq(POLICY_NAME), eq(userId), anyInt(), anyInt()))
                .thenReturn(List.of(version(2, POLICY_JSON_V2), version(1, POLICY_JSON_V1)));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"revision\":2"), "should include revision 2");
        assertTrue(body.contains("\"revision\":1"), "should include revision 1");
        assertTrue(body.contains("\"contentHash\":\"hash-2\""), "should include content hash");
        // Full policy JSON must NOT appear in the list endpoint.
        assertTrue(!body.contains("ssnFilterStrategies"), "list should not include full policy JSON");
    }

    @Test
    void listVersionsPassesPaginationParameters() throws Exception {
        when(policyVersionDataService.findAllByName(eq(POLICY_NAME), eq(userId), eq(5), eq(10)))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions")
                        .header("Authorization", AUTH_HEADER)
                        .param("offset", "5")
                        .param("limit", "10"))
                .andExpect(status().isOk());

        verify(policyVersionDataService).findAllByName(eq(POLICY_NAME), eq(userId), eq(5), eq(10));
    }

    @Test
    void listVersionsReturns404ForNonAdminOwnerParam() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCanListAnotherUsersVersionsViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);
        when(policyVersionDataService.findAllByName(eq(POLICY_NAME), eq(otherUser), anyInt(), anyInt()))
                .thenReturn(List.of(version(1, POLICY_JSON_V1)));

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isOk());

        verify(policyVersionDataService).findAllByName(eq(POLICY_NAME), eq(otherUser), anyInt(), anyInt());
    }

    // ============================================================
    // GET /api/policies/{policyName}/versions/{revision}
    // ============================================================

    @Test
    void getVersionReturns401WithNoAuthHeader() throws Exception {
        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getVersionReturnsFullPolicyJsonForExistingRevision() throws Exception {
        when(policyVersionDataService.findByNameAndRevision(eq(POLICY_NAME), eq(userId), eq(1)))
                .thenReturn(version(1, POLICY_JSON_V1));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions/1")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("REDACT"), "should return the policy JSON for revision 1");
    }

    @Test
    void getVersionReturns404WhenRevisionDoesNotExist() throws Exception {
        when(policyVersionDataService.findByNameAndRevision(anyString(), any(), anyInt()))
                .thenReturn(null);

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions/99")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isNotFound());
    }

    @Test
    void getVersionReturns404ForNonAdminOwnerParam() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/versions/1")
                        .header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());
    }

    // ============================================================
    // GET /api/policies/{policyName}/diff
    // ============================================================

    @Test
    void diffReturns401WithNoAuthHeader() throws Exception {
        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void diffDefaultsToTwoMostRecentVersionsWhenNoParamsGiven() throws Exception {
        when(policyVersionDataService.findTwoMostRecent(eq(POLICY_NAME), eq(userId)))
                .thenReturn(List.of(version(2, POLICY_JSON_V2), version(1, POLICY_JSON_V1)));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"from\":1"), "diff envelope should carry the from revision");
        assertTrue(body.contains("\"to\":2"), "diff envelope should carry the to revision");
        assertTrue(body.contains("\"changes\""), "diff envelope should carry a changes array");
    }

    @Test
    void diffDetectsReplacedFieldValue() throws Exception {
        // V1 uses REDACT; V2 uses MASK. The diff should contain a replace op for the strategy field.
        when(policyVersionDataService.findTwoMostRecent(eq(POLICY_NAME), eq(userId)))
                .thenReturn(List.of(version(2, POLICY_JSON_V2), version(1, POLICY_JSON_V1)));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("replace"), "should report a replace operation");
        assertTrue(body.contains("MASK"), "should show the new value");
    }

    @Test
    void diffReturnsEmptyChangesWhenVersionsAreIdentical() throws Exception {
        when(policyVersionDataService.findTwoMostRecent(eq(POLICY_NAME), eq(userId)))
                .thenReturn(List.of(version(2, POLICY_JSON_V1), version(1, POLICY_JSON_V1)));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"changes\":[]"), "no changes when content is identical");
    }

    @Test
    void diffWithExplicitFromAndToRevisions() throws Exception {
        when(policyVersionDataService.findByNameAndRevision(eq(POLICY_NAME), eq(userId), eq(1)))
                .thenReturn(version(1, POLICY_JSON_V1));
        when(policyVersionDataService.findByNameAndRevision(eq(POLICY_NAME), eq(userId), eq(2)))
                .thenReturn(version(2, POLICY_JSON_V2));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER)
                        .param("from", "1")
                        .param("to", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"from\":1"));
        assertTrue(body.contains("\"to\":2"));
    }

    @Test
    void diffReturnsBadRequestWhenOnlyOneParamProvided() throws Exception {
        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER)
                        .param("from", "1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void diffReturnsBadRequestWhenFewerThanTwoVersionsExist() throws Exception {
        when(policyVersionDataService.findTwoMostRecent(eq(POLICY_NAME), eq(userId)))
                .thenReturn(List.of(version(1, POLICY_JSON_V1)));

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isBadRequest());
    }

    @Test
    void diffReturns404WhenExplicitRevisionDoesNotExist() throws Exception {
        when(policyVersionDataService.findByNameAndRevision(eq(POLICY_NAME), eq(userId), eq(1)))
                .thenReturn(version(1, POLICY_JSON_V1));
        when(policyVersionDataService.findByNameAndRevision(eq(POLICY_NAME), eq(userId), eq(99)))
                .thenReturn(null);

        mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER)
                        .param("from", "1")
                        .param("to", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void diffDetectsAddedField() throws Exception {
        final String withEmail =
                "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]},"
                        + "\"emailAddress\":{}}}";
        when(policyVersionDataService.findTwoMostRecent(eq(POLICY_NAME), eq(userId)))
                .thenReturn(List.of(version(2, withEmail), version(1, POLICY_JSON_V1)));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"op\":\"add\""), "should report an add operation for the new field");
        assertTrue(body.contains("emailAddress"), "should identify the added field");
    }

    @Test
    void diffDetectsRemovedField() throws Exception {
        final String withEmail =
                "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]},"
                        + "\"emailAddress\":{}}}";
        // From has emailAddress; to does not — expect a remove op.
        when(policyVersionDataService.findTwoMostRecent(eq(POLICY_NAME), eq(userId)))
                .thenReturn(List.of(version(2, POLICY_JSON_V1), version(1, withEmail)));

        final String body = mockMvc.perform(get("/api/policies/" + POLICY_NAME + "/diff")
                        .header("Authorization", AUTH_HEADER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"op\":\"remove\""), "should report a remove operation");
        assertTrue(body.contains("emailAddress"), "should identify the removed field");
    }

    // ============================================================
    // POST /api/policies/{policyName}/rollback
    // ============================================================

    @Test
    void rollbackReturns401WithNoAuthHeader() throws Exception {
        mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .param("revision", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rollbackSucceedsAndReturnsNewRevision() throws Exception {
        when(policyDataService.rollback(anyString(), eq(POLICY_NAME), eq(userId), eq(1)))
                .thenReturn(new ServiceResponse("Policy rolled back to revision 1. New revision: 3", true, 200));

        final PolicyEntity live = new PolicyEntity();
        live.setRevision(3);
        when(policyDataService.findOne(eq(POLICY_NAME), eq(userId))).thenReturn(live);

        final String body = mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .header("Authorization", AUTH_HEADER)
                        .param("revision", "1"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"revision\":3"), "response should carry the new revision number");
    }

    @Test
    void rollbackReturns404WhenPolicyDoesNotExist() throws Exception {
        when(policyDataService.rollback(anyString(), eq(POLICY_NAME), eq(userId), eq(1)))
                .thenReturn(new ServiceResponse("Policy does not exist.", false, 404));

        mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .header("Authorization", AUTH_HEADER)
                        .param("revision", "1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rollbackReturns404WhenTargetRevisionDoesNotExist() throws Exception {
        when(policyDataService.rollback(anyString(), eq(POLICY_NAME), eq(userId), eq(99)))
                .thenReturn(new ServiceResponse("Revision 99 does not exist.", false, 404));

        mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .header("Authorization", AUTH_HEADER)
                        .param("revision", "99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rollbackReturns409ForManagedPolicy() throws Exception {
        when(policyDataService.rollback(anyString(), eq(POLICY_NAME), eq(userId), eq(1)))
                .thenReturn(new ServiceResponse("Managed policies cannot be rolled back.", false, 409));

        mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .header("Authorization", AUTH_HEADER)
                        .param("revision", "1"))
                .andExpect(status().isConflict());
    }

    @Test
    void rollbackReturns404ForNonAdminOwnerParam() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeOwnerLookup("other@example.com", otherUser);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .header("Authorization", AUTH_HEADER)
                        .param("revision", "1")
                        .param("owner", "other@example.com"))
                .andExpect(status().isNotFound());

        // The rollback must NOT be attempted when the owner resolution fails.
        verify(policyDataService, never()).rollback(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void adminCanRollBackAnotherUsersPolicy() throws Exception {
        final ObjectId otherUser = new ObjectId();
        makeCallerAdmin();
        makeOwnerLookup("other@example.com", otherUser);

        when(policyDataService.rollback(anyString(), eq(POLICY_NAME), eq(otherUser), eq(1)))
                .thenReturn(new ServiceResponse("Policy rolled back to revision 1. New revision: 2", true, 200));

        final PolicyEntity live = new PolicyEntity();
        live.setRevision(2);
        when(policyDataService.findOne(eq(POLICY_NAME), eq(otherUser))).thenReturn(live);

        mockMvc.perform(post("/api/policies/" + POLICY_NAME + "/rollback")
                        .header("Authorization", AUTH_HEADER)
                        .param("revision", "1")
                        .param("owner", "other@example.com"))
                .andExpect(status().isCreated());

        verify(policyDataService).rollback(anyString(), eq(POLICY_NAME), eq(otherUser), eq(1));
    }

}
