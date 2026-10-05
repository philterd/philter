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
import ai.philterd.philter.api.security.ApiKeyScopeInterceptor;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.AdminAccessConfig;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validation is UserService's and tested there; this covers routing, the owner rules, and refusals. */
@ExtendWith(MockitoExtension.class)
class WebhookApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private UserService userService;
    @Mock private AdminSettingsDataService adminSettingsDataService;
    @Mock private AuditEventPublisher auditEventPublisher;

    private ObjectId callerUserId;
    private ObjectId callerApiKeyId;
    private ApiKeyEntity callerKey;
    private UserEntity caller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        callerUserId = new ObjectId();
        callerApiKeyId = new ObjectId();
        callerKey = keyHolding(ApiKeyScope.all());
        caller = user(callerUserId, "carol", "user");

        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenAnswer(invocation -> callerKey);
        lenient().when(userService.findOneById(callerUserId)).thenReturn(caller);

        mockMvc = MockMvcBuilders.standaloneSetup(new WebhookApiController(apiKeyDataService, apiKeyCache,
                        userService, adminSettingsDataService, auditEventPublisher))
                .addInterceptors(new ApiKeyScopeInterceptor())
                .setControllerAdvice(new RestApiExceptions())
                .build();
    }

    @AfterEach
    void clearOverride() {
        AdminAccessConfig.setOverrideForTesting(null);
    }

    private ApiKeyEntity keyHolding(final Set<String> scopes) {
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(callerApiKeyId);
        key.setUserId(callerUserId);
        key.setApiKeyHash(API_KEY_HASH);
        key.setScopes(new LinkedHashSet<>(scopes));
        return key;
    }

    private static UserEntity user(final ObjectId id, final String username, final String role) {
        final UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        return user;
    }

    private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request
                .header("Authorization", AUTH_HEADER)
                .requestAttr("requestId", "req-hook")
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey));
    }

    private ResultActions setWebhook(final String query, final String body) throws Exception {
        return perform(put("/api/webhook" + query).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("Reading returns the URL and whether a secret is set, never the secret")
    void readsTheWebhookWithoutTheSecret() throws Exception {
        caller.setWebhookUrl("https://93.184.216.34/hook");
        caller.setWebhookSecret("a-very-secret-value-0123");

        final String body = perform(get("/api/webhook"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"url\":\"https://93.184.216.34/hook\"") && body.contains("\"secretSet\":true"), body);
        assertFalse(body.contains("a-very-secret-value"), "the secret must never be returned: " + body);
    }

    @Test
    @DisplayName("With nothing set, the URL is null and no secret is reported")
    void readsAnUnsetWebhook() throws Exception {
        final String body = perform(get("/api/webhook"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"url\":null") && body.contains("\"secretSet\":false"), body);
    }

    @Test
    @DisplayName("Setting passes the administrator's allowlist and the acting key to the shared service path")
    void setsTheWebhookThroughTheService() throws Exception {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setWebhookAllowlist("hooks.example.com");
        when(adminSettingsDataService.findAdminSettings()).thenReturn(settings);
        when(userService.setWebhook("req-hook", caller, "https://hooks.example.com/x", "a-secret-of-16ch",
                "hooks.example.com", "api", callerUserId, callerApiKeyId)).thenReturn(ServiceResponse.success("Webhook saved."));

        setWebhook("", "{\"url\":\"https://hooks.example.com/x\",\"secret\":\"a-secret-of-16ch\"}")
                .andExpect(status().isOk());

        verify(userService).setWebhook("req-hook", caller, "https://hooks.example.com/x", "a-secret-of-16ch",
                "hooks.example.com", "api", callerUserId, callerApiKeyId);
    }

    @Test
    @DisplayName("A webhook the service refuses is a 400 with its reason")
    void reportsTheServicesReason() throws Exception {
        when(userService.setWebhook(anyString(), any(), any(), any(), isNull(), anyString(), any(), any()))
                .thenReturn(ServiceResponse.failure("Secret must be at least 16 characters."));

        final String body = setWebhook("", "{\"url\":\"https://93.184.216.34/x\",\"secret\":\"short\"}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("at least 16"), body);
    }

    @Test
    @DisplayName("Removing goes through the service and answers 204")
    void removesTheWebhook() throws Exception {
        perform(delete("/api/webhook")).andExpect(status().isNoContent());

        verify(userService).removeWebhook("req-hook", caller, "api", callerUserId, callerApiKeyId);
    }

    @Test
    @DisplayName("A non-administrator naming another owner gets a 404 and nothing changes")
    void aNonAdministratorCannotReachAnotherUser() throws Exception {
        AdminAccessConfig.setOverrideForTesting(true);
        final UserEntity bob = user(new ObjectId(), "bob", "user");
        when(userService.findByUsername("bob")).thenReturn(bob);

        setWebhook("?owner=bob", "{\"url\":\"https://93.184.216.34/x\",\"secret\":\"a-secret-of-16ch\"}")
                .andExpect(status().isNotFound());
        perform(delete("/api/webhook?owner=bob")).andExpect(status().isNotFound());

        verify(userService, never()).setWebhook(any(), any(), any(), any(), any(), any(), any(), any());
        verify(userService, never()).removeWebhook(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("An administrator needs cross-user access enabled to reach another user's webhook")
    void anAdministratorNeedsCrossUserAccess() throws Exception {
        caller.setRole("admin");
        final UserEntity bob = user(new ObjectId(), "bob", "user");
        when(userService.findByUsername("bob")).thenReturn(bob);

        AdminAccessConfig.setOverrideForTesting(false);
        perform(delete("/api/webhook?owner=bob")).andExpect(status().isNotFound());
        verify(userService, never()).removeWebhook(any(), any(), any(), any(), any());

        AdminAccessConfig.setOverrideForTesting(true);
        when(userService.findOneById(bob.getId())).thenReturn(bob);
        perform(delete("/api/webhook?owner=bob")).andExpect(status().isNoContent());
        verify(userService).removeWebhook("req-hook", bob, "api", callerUserId, callerApiKeyId);
        verify(auditEventPublisher).auditEvent(eq("req-hook"), eq(AuditLogEvent.ADMIN_CROSS_USER_ACCESS),
                eq(callerUserId), eq(bob.getId()), isNull(), contains("remove webhook"));
    }

    @Test
    @DisplayName("Reading and writing need their own scopes")
    void requiresTheScopes() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.WEBHOOKS_READ.getScope()));

        final String body = perform(delete("/api/webhook"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("webhooks:write"), body);

        callerKey = keyHolding(Set.of(ApiKeyScope.WEBHOOKS_WRITE.getScope()));
        assertTrue(perform(get("/api/webhook")).andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString().contains("webhooks:read"));

        verifyNoInteractions(adminSettingsDataService);
    }

}
