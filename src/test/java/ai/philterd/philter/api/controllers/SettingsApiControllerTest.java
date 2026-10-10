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
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.config.LedgerDeletionConfig;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";

    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private AdminSettingsDataService adminSettingsDataService;
    @Mock private UserService userService;
    @Mock private SigningKeyDataService signingKeyDataService;

    @AfterEach
    void clearOverrides() {
        AdminAccessConfig.setOverrideForTesting(null);
        LedgerDeletionConfig.setOverrideForTesting(null);
    }

    /** A MockMvc for the controller, with the API key authenticating as an administrator. */
    private MockMvc asAdministrator() {
        final ObjectId userId = new ObjectId();
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(new ObjectId());
        key.setUserId(userId);
        lenient().when(apiKeyCache.containsApiKey(EncryptionService.hashSha256(API_KEY))).thenReturn(true);
        lenient().when(apiKeyCache.get(EncryptionService.hashSha256(API_KEY))).thenReturn(key);
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
        return MockMvcBuilders.standaloneSetup(new SettingsApiController(
                        apiKeyDataService, apiKeyCache, adminSettingsDataService, userService, signingKeyDataService))
                .setControllerAdvice(new RestApiExceptions()).build();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readingReportsTheDeploymentFlags(final boolean on) throws Exception {
        AdminAccessConfig.setOverrideForTesting(on);
        LedgerDeletionConfig.setOverrideForTesting(!on);
        when(signingKeyDataService.isExternallyManaged()).thenReturn(on);

        // Each flag differs from the next, so a field reading the wrong flag fails.
        asAdministrator().perform(get("/api/settings").header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crossUserAccessEnabled").value(on))
                .andExpect(jsonPath("$.ledgerDeletionEnabled").value(!on))
                .andExpect(jsonPath("$.signingKeyExternallyManaged").value(on));
    }

    @Test
    void changingTheSettingsIgnoresTheDeploymentFlags() throws Exception {
        AdminAccessConfig.setOverrideForTesting(false);
        LedgerDeletionConfig.setOverrideForTesting(false);
        when(adminSettingsDataService.update(anyString(), any(), any(), any())).thenReturn(List.of());

        asAdministrator().perform(patch("/api/settings").header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"crossUserAccessEnabled\":true,\"ledgerDeletionEnabled\":true,\"signingKeyExternallyManaged\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crossUserAccessEnabled").value(false))
                .andExpect(jsonPath("$.ledgerDeletionEnabled").value(false))
                .andExpect(jsonPath("$.signingKeyExternallyManaged").value(false));

        // Nothing in the request reached the stored settings.
        verify(adminSettingsDataService).update(anyString(), eq(new AdminSettingsDataService.Update(
                null, null, null, null, null, null, null, null, null, null)), any(), any());
    }

    @Test
    void anAdministratorDemotedMidRequestGetsA403() throws Exception {
        final ObjectId userId = new ObjectId();
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(new ObjectId());
        key.setUserId(userId);
        lenient().when(apiKeyCache.containsApiKey(EncryptionService.hashSha256(API_KEY))).thenReturn(true);
        lenient().when(apiKeyCache.get(EncryptionService.hashSha256(API_KEY))).thenReturn(key);
        final UserEntity admin = new UserEntity();
        admin.setId(userId);
        admin.setRole("admin");
        when(userService.findOneById(userId)).thenReturn(admin);
        // The controller's check passed; the service's recheck, against the stored account, does not.
        when(adminSettingsDataService.update(anyString(), any(), any(), any()))
                .thenThrow(new AccessDeniedException("Current administrator authorization required."));

        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SettingsApiController(
                        apiKeyDataService, apiKeyCache, adminSettingsDataService, userService, signingKeyDataService))
                .setControllerAdvice(new RestApiExceptions()).build();

        final String body = mockMvc.perform(patch("/api/settings").header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"signingEnabled\":true}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("administrator"), body);
    }

}
