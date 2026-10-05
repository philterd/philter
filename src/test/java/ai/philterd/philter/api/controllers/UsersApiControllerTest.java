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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * User management is administrator-only, so much of what is tested here is what it refuses: to serve
 * a non-administrator, to take a password, to remove the last administrator, and to mint a key wider
 * than the one asking for it.
 *
 * <p>The scope interceptor is registered alongside the controller rather than left out, because the
 * two refusals a caller has to tell apart (no scope, not an administrator) come from opposite sides of
 * it.
 */
@ExtendWith(MockitoExtension.class)
class UsersApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

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

        final UsersApiController controller = new UsersApiController(
                apiKeyDataService, apiKeyCache, userService, policyDataService, contextDataService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ApiKeyScopeInterceptor())
                .setControllerAdvice(new RestApiExceptions())
                .build();

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
        when(userService.createUser(anyString(), anyString(), any(), isNull(), anyString(),
                any(), any(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(ServiceResponse.success("User created."));
    }

    private UserEntity targetUser(final String username) {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setUsername(username);
        return user;
    }

    // ----- creating a user -----

    @Test
    @DisplayName("An administrator creates a user, with no password, as a non-administrator by default")
    void createsANonAdministratorUser() throws Exception {
        callerIsAdministrator(true);
        userCreationSucceeds();

        final String body = createUser("{\"username\":\"ci\",\"email\":\"ci@example.com\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"username\":\"ci\""), "the response must name the user: " + body);
        assertTrue(body.contains("\"role\":\"user\""), "the response must state the role: " + body);
        verify(userService).createUser(eq("req-provision"), eq("ci"), eq("ci@example.com"), isNull(),
                eq("user"), any(), any(), eq("api"), eq(false), eq(callerUserId), eq(callerApiKeyId));
    }

    @Test
    @DisplayName("The creation is audited with the calling administrator as the principal")
    void recordsTheActingAdministrator() throws Exception {
        callerIsAdministrator(true);
        userCreationSucceeds();

        createUser("{\"username\":\"ci\"}").andExpect(status().isCreated());

        // The acting principal is passed through to the user_created event; without it the audit log
        // says a user appeared and not who made it.
        verify(userService).createUser(anyString(), anyString(), any(), isNull(), anyString(),
                any(), any(), anyString(), anyBoolean(), eq(callerUserId), eq(callerApiKeyId));
    }

    @Test
    @DisplayName("An administrator can create an administrator")
    void createsAnAdministrator() throws Exception {
        callerIsAdministrator(true);
        userCreationSucceeds();

        final String body = createUser("{\"username\":\"ops\",\"role\":\"Admin\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"role\":\"admin\""), "the role is normalized: " + body);
        verify(userService).createUser(anyString(), eq("ops"), any(), isNull(), eq("admin"),
                any(), any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("A role other than user or admin is refused")
    void createUserRefusesAnUnknownRole() throws Exception {
        callerIsAdministrator(true);

        createUser("{\"username\":\"ci\",\"role\":\"root\"}").andExpect(status().isBadRequest());

        verify(userService, never()).createUser(anyString(), anyString(), any(), any(), anyString(),
                any(), any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("The username reserved for the calling key's user cannot be taken")
    void createUserRefusesTheReservedUsername() throws Exception {
        callerIsAdministrator(true);

        createUser("{\"username\":\"Me\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A non-administrator is refused, and told that is what is missing")
    void createUserRefusesANonAdministrator() throws Exception {
        callerIsAdministrator(false);

        final String body = createUser("{\"username\":\"ci\"}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("administrator"), "the refusal must say what is required: " + body);
        assertFalse(body.contains("scope"), "this refusal is not about the key's scopes: " + body);
        verify(userService, never()).createUser(anyString(), anyString(), any(), any(), anyString(),
                any(), any(), anyString(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("A key without users:write is refused, and told which scope is missing")
    void createUserRefusesAKeyWithoutTheScope() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.REDACT.getScope()));

        final String body = createUser("{\"username\":\"ci\"}")
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
        when(userService.createUser(anyString(), anyString(), any(), isNull(), anyString(),
                any(), any(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(ServiceResponse.failure("User already exists."));

        final String body = createUser("{\"username\":\"ci\"}")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("already exists"), "the conflict must say why: " + body);
    }

    @Test
    @DisplayName("A username is required")
    void createUserRequiresAUsername() throws Exception {
        callerIsAdministrator(true);

        createUser("{\"email\":\"ci@example.com\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("A password is refused rather than silently ignored")
    void createUserRefusesAPassword() throws Exception {
        callerIsAdministrator(true);

        final String body = createUser("{\"username\":\"ci\",\"password\":\"a-password-of-at-least-16-characters\"}")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("API keys"), "the refusal must say how users authenticate: " + body);
        verify(userService, never()).createUser(anyString(), anyString(), any(), any(), anyString(),
                any(), any(), anyString(), anyBoolean(), any(), any());
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

    // ----- reading users -----

    private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request
                .header("Authorization", AUTH_HEADER)
                .requestAttr("requestId", "req-users")
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey));
    }

    private UserEntity storedUser(final String username, final String role, final boolean deactivated) {
        final UserEntity user = targetUser(username);
        user.setEmail(username + "@example.com");
        user.setRole(role);
        user.setDeactivated(deactivated);
        user.setPassword("$2a$10$storedhashstoredhashstoredhashstoredhashstoredhashst");
        user.setMfaSecret("JBSWY3DPEHPK3PXP");
        return user;
    }

    @Test
    @DisplayName("An administrator lists users, deactivated ones included, with the total")
    void listsUsers() throws Exception {
        callerIsAdministrator(true);
        when(userService.findAll(0, 25)).thenReturn(List.of(
                storedUser("alice", "admin", false), storedUser("bob", "user", true)));
        when(userService.count()).thenReturn(2);

        final String body = perform(get("/api/users"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"total\":2"), body);
        assertTrue(body.contains("\"username\":\"bob\"") && body.contains("\"active\":false"),
                "a deactivated user is listed and marked: " + body);
        assertFalse(body.contains("password") || body.contains("storedhash") || body.contains("JBSWY3DP")
                || body.contains("mfa"), "no password, hash, or MFA secret may leave the API: " + body);
    }

    @Test
    @DisplayName("Paging is clamped to the API's limits")
    void listingClampsPaging() throws Exception {
        callerIsAdministrator(true);
        when(userService.findAll(0, 100)).thenReturn(List.of());

        perform(get("/api/users").param("offset", "-5").param("limit", "1000")).andExpect(status().isOk());

        verify(userService).findAll(0, 100);
    }

    @Test
    @DisplayName("A non-administrator cannot list users")
    void listingRefusesANonAdministrator() throws Exception {
        callerIsAdministrator(false);

        final String body = perform(get("/api/users"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("administrator"), body);
        verify(userService, never()).findAll(anyInt(), anyInt());
    }

    @Test
    @DisplayName("A key without users:read cannot list users, and is told which scope")
    void listingRefusesAKeyWithoutTheScope() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.USERS_WRITE.getScope()));

        final String body = perform(get("/api/users"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("users:read"), body);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("Any key with users:read can read its own user, without being an administrator")
    void readsTheCallingKeysUser() throws Exception {
        final UserEntity self = storedUser("carol", "user", false);
        self.setId(callerUserId);
        when(userService.findOneById(callerUserId)).thenReturn(self);
        callerKey = keyHolding(Set.of(ApiKeyScope.USERS_READ.getScope()));

        final String body = perform(get("/api/users/me"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"username\":\"carol\"") && body.contains("\"role\":\"user\""), body);
        assertFalse(body.contains("storedhash") || body.contains("JBSWY3DP"), body);
    }

    @Test
    @DisplayName("An administrator reads one user, deactivated or not")
    void readsAUser() throws Exception {
        callerIsAdministrator(true);
        when(userService.findAnyByUsername("bob")).thenReturn(storedUser("bob", "user", true));

        final String body = perform(get("/api/users/bob"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"active\":false") && body.contains("\"email\":\"bob@example.com\""), body);
        assertTrue(body.contains("\"created\""), "the creation time is returned: " + body);
    }

    @Test
    @DisplayName("Reading a user that does not exist is not found")
    void readingAMissingUserIsNotFound() throws Exception {
        callerIsAdministrator(true);

        perform(get("/api/users/nobody")).andExpect(status().isNotFound());
    }

    // ----- changing users -----

    private ResultActions setRole(final String username, final String body) throws Exception {
        return perform(put("/api/users/" + username + "/role")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("An administrator promotes a user, audited with the administrator as the principal")
    void promotesAUser() throws Exception {
        callerIsAdministrator(true);
        final UserEntity bob = storedUser("bob", "user", false);
        when(userService.findAnyByUsername("bob")).thenReturn(bob);
        when(userService.setUserRole("req-users", bob, "admin", "api", callerUserId, callerApiKeyId))
                .thenReturn(ServiceResponse.success("User role updated."));

        setRole("bob", "{\"role\":\"admin\"}").andExpect(status().isOk());

        verify(userService).setUserRole("req-users", bob, "admin", "api", callerUserId, callerApiKeyId);
    }

    @Test
    @DisplayName("Demoting the last active administrator is a conflict")
    void cannotDemoteTheLastAdministrator() throws Exception {
        callerIsAdministrator(true);
        final UserEntity alice = storedUser("alice", "admin", false);
        when(userService.findAnyByUsername("alice")).thenReturn(alice);
        when(userService.setUserRole(anyString(), eq(alice), eq("user"), anyString(), any(), any()))
                .thenReturn(ServiceResponse.failure(UserService.LAST_ADMIN_MESSAGE));

        final String body = setRole("alice", "{\"role\":\"user\"}")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("last active administrator"), body);
    }

    @Test
    @DisplayName("Setting an unknown or missing role is refused")
    void setRoleRefusesABadRole() throws Exception {
        callerIsAdministrator(true);

        setRole("bob", "{\"role\":\"superuser\"}").andExpect(status().isBadRequest());
        setRole("bob", "{}").andExpect(status().isBadRequest());

        verify(userService, never()).setUserRole(anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("A non-administrator cannot set a role")
    void setRoleRefusesANonAdministrator() throws Exception {
        callerIsAdministrator(false);

        setRole("bob", "{\"role\":\"admin\"}").andExpect(status().isForbidden());

        verify(userService, never()).setUserRole(anyString(), any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("An administrator deactivates a user, audited with the administrator as the principal")
    void deactivatesAUser() throws Exception {
        callerIsAdministrator(true);
        final UserEntity bob = storedUser("bob", "user", false);
        when(userService.findAnyByUsername("bob")).thenReturn(bob);
        when(userService.deactivateUser("req-users", bob, "api", callerUserId, callerApiKeyId))
                .thenReturn(ServiceResponse.success("User deactivated."));

        perform(post("/api/users/bob/deactivate")).andExpect(status().isOk());

        verify(userService).deactivateUser("req-users", bob, "api", callerUserId, callerApiKeyId);
    }

    @Test
    @DisplayName("An administrator cannot deactivate their own user")
    void cannotDeactivateSelf() throws Exception {
        callerIsAdministrator(true);
        final UserEntity self = storedUser("alice", "admin", false);
        self.setId(callerUserId);
        when(userService.findAnyByUsername("alice")).thenReturn(self);

        perform(post("/api/users/alice/deactivate")).andExpect(status().isConflict());

        verify(userService, never()).deactivateUser(anyString(), any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Deactivating the last active administrator is a conflict")
    void cannotDeactivateTheLastAdministrator() throws Exception {
        callerIsAdministrator(true);
        final UserEntity other = storedUser("ops", "admin", false);
        when(userService.findAnyByUsername("ops")).thenReturn(other);
        when(userService.deactivateUser(anyString(), eq(other), anyString(), any(), any()))
                .thenReturn(ServiceResponse.failure(UserService.LAST_ADMIN_MESSAGE));

        perform(post("/api/users/ops/deactivate")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("An administrator reactivates a user")
    void reactivatesAUser() throws Exception {
        callerIsAdministrator(true);
        final UserEntity bob = storedUser("bob", "user", true);
        when(userService.findAnyByUsername("bob")).thenReturn(bob);
        when(userService.reactivateUser("req-users", bob, "api", callerUserId, callerApiKeyId))
                .thenReturn(ServiceResponse.success("User reactivated."));

        perform(post("/api/users/bob/reactivate")).andExpect(status().isOk());

        verify(userService).reactivateUser("req-users", bob, "api", callerUserId, callerApiKeyId);
    }

    @Test
    @DisplayName("Deactivating or reactivating a user that does not exist is not found")
    void changingAMissingUserIsNotFound() throws Exception {
        callerIsAdministrator(true);

        perform(post("/api/users/nobody/deactivate")).andExpect(status().isNotFound());
        perform(post("/api/users/nobody/reactivate")).andExpect(status().isNotFound());
    }

}
