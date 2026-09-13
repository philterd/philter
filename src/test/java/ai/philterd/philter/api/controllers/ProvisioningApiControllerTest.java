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
import ai.philterd.philter.config.ProvisioningConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The provisioning endpoints are a way around the dashboard login and its MFA, so what is tested here
 * is mostly what they refuse: to exist at all without the deployment opting in, to serve a
 * non-administrator, to create an administrator, and to mint a key wider than the one asking for it.
 *
 * <p>The scope interceptor is registered alongside the controller rather than left out, because the
 * two refusals a caller has to tell apart (no scope, not an administrator) come from opposite sides of
 * it.
 */
@ExtendWith(MockitoExtension.class)
class ProvisioningApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    private static final String PASSWORD = "a-password-of-at-least-16-characters";

    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private UserService userService;
    @Mock private PolicyDataService policyDataService;
    @Mock private ContextDataService contextDataService;

    private ObjectId callerUserId;
    private ObjectId callerApiKeyId;
    private ApiKeyEntity callerKey;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {

        callerUserId = new ObjectId();
        callerApiKeyId = new ObjectId();
        callerKey = keyHolding(ApiKeyScope.all());

        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenAnswer(invocation -> callerKey);

        // On unless a test says otherwise: what the switch does when it is off is its own test.
        ProvisioningConfig.setOverrideForTesting(true);

        final ProvisioningApiController controller = new ProvisioningApiController(
                apiKeyDataService, apiKeyCache, userService, policyDataService, contextDataService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ApiKeyScopeInterceptor())
                .setControllerAdvice(new RestApiExceptions())
                .build();

    }

    @AfterEach
    void clearProvisioningOverride() {
        ProvisioningConfig.setOverrideForTesting(null);
    }

    private ApiKeyEntity keyHolding(final Set<String> scopes) {
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(callerApiKeyId);
        key.setUserId(callerUserId);
        key.setApiKeyHash(API_KEY_HASH);
        key.setScopes(new LinkedHashSet<>(scopes));
        return key;
    }

    private void callerIsAdministrator(final boolean admin) {
        final UserEntity user = new UserEntity();
        user.setId(callerUserId);
        user.setRole(admin ? "admin" : "user");
        when(userService.findOneById(callerUserId)).thenReturn(user);
    }

    private ResultActions createUser(final String body) throws Exception {
        return mockMvc.perform(post("/api/users")
                .header("Authorization", AUTH_HEADER)
                .requestAttr("requestId", "req-provision")
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions createApiKey(final String username, final String body) throws Exception {
        return mockMvc.perform(post("/api/users/" + username + "/api-keys")
                .header("Authorization", AUTH_HEADER)
                .requestAttr("requestId", "req-provision")
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void userCreationSucceeds() {
        when(userService.createUser(anyString(), anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), anyBoolean(), any()))
                .thenReturn(ServiceResponse.success("User created."));
    }

    private UserEntity targetUser(final String username) {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setUsername(username);
        return user;
    }

    // ----- the kill switch -----

    @Test
    @DisplayName("Provisioning is off unless the deployment sets the environment variable")
    void theSwitchIsOffByDefault() {
        ProvisioningConfig.setOverrideForTesting(null);
        assertFalse(ProvisioningConfig.isProvisioningApiEnabled(),
                "the provisioning endpoints must not exist in a deployment that has not opted in");
    }

    @Test
    @DisplayName("With the switch off, creating a user answers as though the endpoint is not there")
    void creatingAUserIsNotFoundWhenProvisioningIsDisabled() throws Exception {
        ProvisioningConfig.setOverrideForTesting(false);

        createUser("{\"username\":\"ci\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isNotFound());

        // Not even the caller's role is looked at: the deployment did not opt in, so there is nothing
        // to decide.
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("With the switch off, creating an API key answers as though the endpoint is not there")
    void creatingAKeyIsNotFoundWhenProvisioningIsDisabled() throws Exception {
        ProvisioningConfig.setOverrideForTesting(false);

        createApiKey("ci", "{\"scopes\":[\"redact\"]}").andExpect(status().isNotFound());

        verifyNoInteractions(userService);
        verifyNoInteractions(apiKeyDataService);
    }

    // ----- creating a user -----

    @Test
    @DisplayName("An administrator creates a non-administrator user")
    void createsANonAdministratorUser() throws Exception {
        callerIsAdministrator(true);
        userCreationSucceeds();

        final String body = createUser("{\"username\":\"ci\",\"email\":\"ci@example.com\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"username\":\"ci\""), "the response must name the user: " + body);
        assertTrue(body.contains("\"role\":\"user\""), "the response must state the role: " + body);
        verify(userService).createUser(eq("req-provision"), eq("ci"), eq("ci@example.com"), eq(PASSWORD),
                eq("user"), any(), any(), eq("api"), eq(false), eq(callerUserId));
    }

    @Test
    @DisplayName("The creation is audited with the calling administrator as the principal")
    void recordsTheActingAdministrator() throws Exception {
        callerIsAdministrator(true);
        userCreationSucceeds();

        createUser("{\"username\":\"ci\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isCreated());

        // The acting principal is passed through to the user_created event; without it the audit log
        // says a user appeared and not who made it.
        verify(userService).createUser(anyString(), anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), anyBoolean(), eq(callerUserId));
    }

    @Test
    @DisplayName("A role in the body cannot make the new user an administrator")
    void cannotCreateAnAdministrator() throws Exception {
        callerIsAdministrator(true);
        userCreationSucceeds();

        createUser("{\"username\":\"ci\",\"password\":\"" + PASSWORD + "\",\"role\":\"admin\"}")
                .andExpect(status().isCreated());

        // The role is not a parameter, so the field is not read and the user is created as a
        // non-administrator whatever the body says.
        verify(userService).createUser(anyString(), anyString(), any(), anyString(), eq("user"),
                any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A non-administrator is refused, and told that is what is missing")
    void createUserRefusesANonAdministrator() throws Exception {
        callerIsAdministrator(false);

        final String body = createUser("{\"username\":\"ci\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("administrator"), "the refusal must say what is required: " + body);
        assertFalse(body.contains("scope"), "this refusal is not about the key's scopes: " + body);
        verify(userService, never()).createUser(anyString(), anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("A key without users:write is refused, and told which scope is missing")
    void createUserRefusesAKeyWithoutTheScope() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.REDACT.getScope()));

        final String body = createUser("{\"username\":\"ci\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        // Distinguishable from the refusal above: one names the scope, the other the role.
        assertTrue(body.contains("users:write"), "the refusal must name the missing scope: " + body);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("A username already in use is a conflict, not a new user")
    void createUserReportsADuplicateUsername() throws Exception {
        callerIsAdministrator(true);
        when(userService.createUser(anyString(), anyString(), any(), anyString(), anyString(),
                any(), any(), anyString(), anyBoolean(), any()))
                .thenReturn(ServiceResponse.failure("User already exists."));

        final String body = createUser("{\"username\":\"ci\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("already exists"), "the conflict must say why: " + body);
    }

    @Test
    @DisplayName("A username is required")
    void createUserRequiresAUsername() throws Exception {
        callerIsAdministrator(true);

        createUser("{\"password\":\"" + PASSWORD + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A password shorter than the dashboard's minimum is refused")
    void createUserEnforcesThePasswordMinimum() throws Exception {
        callerIsAdministrator(true);

        // Accepting a password the dashboard would reject would leave an account that can hold a key
        // but cannot pass the login it is otherwise governed by.
        final String body = createUser("{\"username\":\"ci\",\"password\":\"short\"}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("16"), "the refusal must state the minimum: " + body);
    }

    // ----- creating an API key -----

    @Test
    @DisplayName("An administrator mints a key for another user, and the secret is returned once")
    void createsAKeyForAnotherUser() throws Exception {
        callerIsAdministrator(true);
        final UserEntity target = targetUser("ci");
        when(userService.findByUsername("ci")).thenReturn(target);
        when(apiKeyDataService.createApiKey(anyString(), eq(target.getId()), anyString(), any(), anyString()))
                .thenReturn(new ServiceResponse("sk_mintedmintedmintedmintedminted01", true, 200));

        final String body = createApiKey("ci", "{\"scopes\":[\"redact\",\"policies:read\"]}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("sk_mintedmintedmintedmintedminted01"),
                "the key value is returned here and nowhere else: " + body);
        assertTrue(body.contains("\"username\":\"ci\""), "the response must name the owner: " + body);
        assertTrue(body.contains("redact") && body.contains("policies:read"),
                "the response must state what the key can do: " + body);
    }

    @Test
    @DisplayName("The key creation is audited with the administrator and key that asked for it")
    void recordsTheActingPrincipalOnTheKey() throws Exception {
        callerIsAdministrator(true);
        final UserEntity target = targetUser("ci");
        when(userService.findByUsername("ci")).thenReturn(target);
        when(apiKeyDataService.createApiKey(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new ServiceResponse("sk_mintedmintedmintedmintedminted01", true, 200));

        createApiKey("ci", "{\"scopes\":[\"redact\"]}").andExpect(status().isCreated());

        verify(apiKeyDataService).createApiKey(eq("req-provision"), eq(target.getId()), eq("api"),
                eq(Set.of("redact")), contains("created by user: " + callerUserId));
    }

    @Test
    @DisplayName("A key cannot be granted a scope the calling key does not hold")
    void cannotGrantAScopeTheCallerDoesNotHold() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope(), ApiKeyScope.REDACT.getScope()));
        callerIsAdministrator(true);

        final String body = createApiKey("ci", "{\"scopes\":[\"redact\",\"ledger:export\"]}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("ledger:export"), "the refusal must name the scope not held: " + body);
        assertFalse(body.contains("redact\""), "only the scope that was refused is named: " + body);
        verify(apiKeyDataService, never()).createApiKey(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("The subset check runs before the user is resolved")
    void refusesTheScopeWithoutRevealingWhetherTheUserExists() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope()));
        callerIsAdministrator(true);

        createApiKey("does-not-exist", "{\"scopes\":[\"ledger:export\"]}").andExpect(status().isForbidden());

        // A caller who could not grant the scope anyway must not learn from the status code whether a
        // username exists.
        verify(userService, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("A non-administrator is refused, and told that is what is missing")
    void createKeyRefusesANonAdministrator() throws Exception {
        callerIsAdministrator(false);

        final String body = createApiKey("ci", "{\"scopes\":[\"redact\"]}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("administrator"), "the refusal must say what is required: " + body);
        verify(apiKeyDataService, never()).createApiKey(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("A key without api-keys:write is refused, and told which scope is missing")
    void createKeyRefusesAKeyWithoutTheScope() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.REDACT.getScope()));

        final String body = createApiKey("ci", "{\"scopes\":[\"redact\"]}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("api-keys:write"), "the refusal must name the missing scope: " + body);
        verifyNoInteractions(apiKeyDataService);
    }

    @Test
    @DisplayName("An unknown user is a 404")
    void createKeyReportsAnUnknownUser() throws Exception {
        callerIsAdministrator(true);
        when(userService.findByUsername("nobody")).thenReturn(null);

        createApiKey("nobody", "{\"scopes\":[\"redact\"]}").andExpect(status().isNotFound());

        verify(apiKeyDataService, never()).createApiKey(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("A key must be asked for with at least one scope")
    void createKeyRequiresScopes() throws Exception {
        callerIsAdministrator(true);

        createApiKey("ci", "{\"scopes\":[]}").andExpect(status().isBadRequest());
        createApiKey("ci", "{}").andExpect(status().isBadRequest());

        verify(apiKeyDataService, never()).createApiKey(anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("A scope that is not a scope is refused rather than dropped")
    void createKeyRefusesAnUnknownScope() throws Exception {
        callerIsAdministrator(true);

        // Dropping it would mint a key quietly narrower than the one that was asked for, and the
        // failure would surface later in whatever integration it was made for.
        final String body = createApiKey("ci", "{\"scopes\":[\"redact\",\"ledger:everything\"]}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("ledger:everything"), "the refusal must name the value: " + body);
        verify(apiKeyDataService, never()).createApiKey(anyString(), any(), anyString(), any(), anyString());
    }

}
