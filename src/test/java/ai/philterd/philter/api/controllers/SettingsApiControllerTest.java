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
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SettingsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";

    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private AdminSettingsDataService adminSettingsDataService;
    @Mock private UserService userService;

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
        when(adminSettingsDataService.update(any(), any(), any()))
                .thenThrow(new AccessDeniedException("Current administrator authorization required."));

        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SettingsApiController(
                        apiKeyDataService, apiKeyCache, adminSettingsDataService, userService))
                .setControllerAdvice(new RestApiExceptions()).build();

        final String body = mockMvc.perform(patch("/api/settings").header("Authorization", "Bearer " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"signingEnabled\":true}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("administrator"), body);
    }

}
