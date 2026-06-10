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
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.springframework.http.HttpMethod;
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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that the custom-lists endpoints scope queries to the owning user id (getUserId), not the
 * API key's own id (getId).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomListsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private CustomListDataService customListService;
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

        final CustomListsApiController controller = new CustomListsApiController(
                customListService, userService, apiKeyDataService, auditEventPublisher, apiKeyCache, new Gson());

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
        when(customListService.findAll(eq(userId))).thenReturn(Collections.emptyList());

        // The endpoint reads the requestId request attribute set by the auth filter.
        mockMvc.perform(get("/api/lists").header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-1"))
                .andExpect(status().isOk());

        verify(customListService).findAll(eq(userId));
    }

    @Test
    void deleteScopesToOwningUserId() throws Exception {
        when(customListService.findOneByName(eq("my-list"), eq(userId))).thenReturn(null);

        mockMvc.perform(request(HttpMethod.DELETE, "/api/lists/my-list")
                        .header("Authorization", AUTH_HEADER)
                        .requestAttr("requestId", "req-2"))
                .andExpect(status().isNotFound());

        // The existence check must be scoped to the owning user.
        verify(customListService).findOneByName(eq("my-list"), eq(userId));
    }

    // ----- Admin cross-user access via the owner parameter -----

    @Test
    void adminCanListAnotherUsersListsViaOwner() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
        final UserEntity owner = new UserEntity();
        owner.setId(otherUser);
        owner.setEmail("other@example.com");
        when(userService.findByUsername("other@example.com")).thenReturn(owner);
        when(customListService.findAll(eq(otherUser))).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/lists").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-admin"))
                .andExpect(status().isOk());

        verify(customListService).findAll(eq(otherUser));
    }

    @Test
    void nonAdminNamingAnotherOwnerGets404() throws Exception {
        final ObjectId otherUser = new ObjectId();
        final UserEntity owner = new UserEntity();
        owner.setId(otherUser);
        owner.setEmail("other@example.com");
        when(userService.findByUsername("other@example.com")).thenReturn(owner);
        final UserEntity caller = new UserEntity();
        caller.setId(userId);
        caller.setRole("user");
        when(userService.findOneById(userId)).thenReturn(caller);

        mockMvc.perform(get("/api/lists").header("Authorization", AUTH_HEADER)
                        .param("owner", "other@example.com")
                        .requestAttr("requestId", "req-forbidden"))
                .andExpect(status().isNotFound());
    }

}
