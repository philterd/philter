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

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What is mostly tested here is what key management refuses: to mint, re-scope, or revoke beyond the
 * calling key's own scopes, to let a non-administrator reach another user's keys, and to let a key
 * revoke itself.
 *
 * <p>The scope interceptor is registered alongside the controller rather than left out, because the
 * two refusals a caller has to tell apart (no scope, not an administrator) come from opposite sides of
 * it.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeysApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private UserService userService;

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

        final ApiKeysApiController controller = new ApiKeysApiController(apiKeyDataService, apiKeyCache, userService);

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

    private static final String MINTED = "sk_mintedmintedmintedmintedminted01";

    private ResultActions createApiKey(final String username, final String body) throws Exception {
        return mockMvc.perform(post("/api/users/" + username + "/api-keys")
                .header("Authorization", AUTH_HEADER)
                .requestAttr("requestId", "req-provision")
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions perform(final MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request
                .header("Authorization", AUTH_HEADER)
                .requestAttr("requestId", "req-keys")
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey));
    }

    private UserEntity targetUser(final String username) {
        final UserEntity user = new UserEntity();
        user.setId(new ObjectId());
        user.setUsername(username);
        return user;
    }

    /** Stubs a successful mint and the lookup that returns the new key's ID. */
    private ObjectId mintSucceeds() {
        final ObjectId mintedId = new ObjectId();
        when(apiKeyDataService.createApiKey(anyString(), any(), anyString(), any(), anyString()))
                .thenReturn(new ServiceResponse(MINTED, true, 200));
        final ApiKeyEntity minted = new ApiKeyEntity();
        minted.setId(mintedId);
        when(apiKeyDataService.findOneByApiKey(MINTED)).thenReturn(minted);
        return mintedId;
    }

    /** A stored key belonging to {@code owner}, holding {@code scopes}, returned when looked up by ID. */
    private ApiKeyEntity storedKey(final ObjectId owner, final Set<String> scopes) {
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(new ObjectId());
        key.setUserId(owner);
        key.setApiKeyPrefix("sk_abcdefghi...");
        key.setScopes(new LinkedHashSet<>(scopes));
        key.setTimestamp(new Date());
        lenient().when(apiKeyDataService.findOneById(key.getId())).thenReturn(key);
        return key;
    }

    private ResultActions setScopes(final ApiKeyEntity key, final String body) throws Exception {
        return perform(put("/api/api-keys/" + key.getId().toHexString() + "/scopes")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ----- creating an API key -----

    @Test
    @DisplayName("An administrator mints a key for another user, and the secret is returned once")
    void createsAKeyForAnotherUser() throws Exception {
        callerIsAdministrator(true);
        final UserEntity target = targetUser("ci");
        when(userService.findByUsername("ci")).thenReturn(target);
        final ObjectId mintedId = mintSucceeds();

        final String body = createApiKey("ci", "{\"scopes\":[\"redact\",\"policies:read\"]}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(MINTED),
                "the key value is returned here and nowhere else: " + body);
        assertTrue(body.contains("\"username\":\"ci\""), "the response must name the owner: " + body);
        assertTrue(body.contains("redact") && body.contains("policies:read"),
                "the response must state what the key can do: " + body);
        assertTrue(body.contains("\"id\":\"" + mintedId.toHexString() + "\""),
                "the response must carry the ID used to re-scope or revoke the key: " + body);
    }

    @Test
    @DisplayName("The key creation is audited with the administrator and key that asked for it")
    void recordsTheActingPrincipalOnTheKey() throws Exception {
        callerIsAdministrator(true);
        final UserEntity target = targetUser("ci");
        when(userService.findByUsername("ci")).thenReturn(target);
        mintSucceeds();

        createApiKey("ci", "{\"scopes\":[\"redact\"]}").andExpect(status().isCreated());

        verify(apiKeyDataService).createApiKey(eq("req-provision"), eq(target.getId()), eq("api"),
                eq(Set.of("redact")), contains("created by user: " + callerUserId + ", api_key: " + callerApiKeyId));
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

    // ----- creating a key for oneself -----

    @Test
    @DisplayName("A non-administrator mints a key for its own user, no wider than itself")
    void createsAnOwnKeyWithoutAnAdministrator() throws Exception {
        final UserEntity self = targetUser("carol");
        self.setId(callerUserId);
        when(userService.findOneById(callerUserId)).thenReturn(self);
        mintSucceeds();

        final String body = perform(post("/api/api-keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"redact\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(MINTED) && body.contains("\"username\":\"carol\""), body);
        verify(apiKeyDataService).createApiKey(eq("req-keys"), eq(callerUserId), eq("api"), eq(Set.of("redact")),
                contains("api_key: " + callerApiKeyId));
    }

    @Test
    @DisplayName("A key cannot mint itself a wider key")
    void cannotMintAnOwnKeyWiderThanItself() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope(), ApiKeyScope.REDACT.getScope()));

        final String body = perform(post("/api/api-keys").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scopes\":[\"redact\",\"reidentify\"]}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("reidentify"), body);
        verify(apiKeyDataService, never()).createApiKey(anyString(), any(), anyString(), any(), anyString());
    }

    // ----- listing keys -----

    @Test
    @DisplayName("A caller lists its own user's keys, with the prefix and never the key or hash")
    void listsOwnKeys() throws Exception {
        final ApiKeyEntity key = storedKey(callerUserId, Set.of("redact"));
        key.setApiKeyHash("0123456789abcdef-hash");
        key.setBootstrap(true);
        when(apiKeyDataService.findAllBySession(callerUserId, 0, 25, (Boolean) null, false)).thenReturn(List.of(key));
        when(apiKeyDataService.countBySession(callerUserId, (Boolean) null)).thenReturn(1);

        final String body = perform(get("/api/api-keys"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"id\":\"" + key.getId().toHexString() + "\""), body);
        assertTrue(body.contains("\"prefix\":\"sk_abcdefghi...\"") && body.contains("\"bootstrap\":true")
                && body.contains("\"created\"") && body.contains("\"total\":1"), body);
        assertFalse(body.contains("hash") || body.contains("\"apiKey\""), "no secret or hash may be listed: " + body);
        verifyNoInteractions(userService);
    }

    @Test
    @DisplayName("session filters the listing, and total counts only the matching keys")
    void filtersBySession() throws Exception {
        final ApiKeyEntity key = storedKey(callerUserId, Set.of("redact"));
        when(apiKeyDataService.findAllBySession(callerUserId, 0, 25, Boolean.FALSE, false)).thenReturn(List.of(key));
        when(apiKeyDataService.countBySession(callerUserId, Boolean.FALSE)).thenReturn(1);

        final String body = perform(get("/api/api-keys").param("session", "false"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("\"total\":1"), body);
        verify(apiKeyDataService).findAllBySession(callerUserId, 0, 25, Boolean.FALSE, false);
    }

    @Test
    @DisplayName("Listing requires api-keys:read")
    void listingRequiresTheReadScope() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope()));

        final String body = perform(get("/api/api-keys"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("api-keys:read"), body);
    }

    @Test
    @DisplayName("An administrator lists another user's keys")
    void anAdministratorListsAnotherUsersKeys() throws Exception {
        callerIsAdministrator(true);
        final UserEntity bob = targetUser("bob");
        when(userService.findAnyByUsername("bob")).thenReturn(bob);
        final ApiKeyEntity bobsKey = storedKey(bob.getId(), Set.of("redact"));
        when(apiKeyDataService.findAllBySession(bob.getId(), 0, 25, (Boolean) null, false)).thenReturn(List.of(bobsKey));
        when(apiKeyDataService.countBySession(bob.getId(), (Boolean) null)).thenReturn(1);

        perform(get("/api/users/bob/api-keys")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A non-administrator cannot list another user's keys")
    void aNonAdministratorCannotListAnotherUsersKeys() throws Exception {
        callerIsAdministrator(false);

        perform(get("/api/users/bob/api-keys")).andExpect(status().isForbidden());

        verify(apiKeyDataService, never()).findAll(any(), anyInt(), anyInt());
    }

    // ----- changing scopes -----

    @Test
    @DisplayName("A caller narrows one of its own keys, audited with the calling key")
    void changesTheScopesOfAnOwnKey() throws Exception {
        final ApiKeyEntity key = storedKey(callerUserId, Set.of("redact", "policies:read"));
        when(apiKeyDataService.updateScopes(eq("req-keys"), eq(callerUserId), eq(key), eq(Set.of("redact")), eq("api"),
                contains("api_key: " + callerApiKeyId))).thenReturn(ServiceResponse.success());

        setScopes(key, "{\"scopes\":[\"redact\"]}").andExpect(status().isOk());

        verify(apiKeyDataService).updateScopes(eq("req-keys"), eq(callerUserId), eq(key), eq(Set.of("redact")), eq("api"),
                contains("api_key: " + callerApiKeyId));
    }

    @Test
    @DisplayName("The key making the request cannot change its own scopes, even to narrow them")
    void cannotChangeItsOwnScopes() throws Exception {
        lenient().when(apiKeyDataService.findOneById(callerKey.getId())).thenReturn(callerKey);

        final String body = setScopes(callerKey, "{\"scopes\":[\"redact\"]}")
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("making the request"), body);
        verify(apiKeyDataService, never()).updateScopes(anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("A key cannot be widened beyond the calling key")
    void cannotWidenAKeyBeyondTheCaller() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope(), ApiKeyScope.REDACT.getScope()));
        final ApiKeyEntity key = storedKey(callerUserId, Set.of("redact"));

        final String body = setScopes(key, "{\"scopes\":[\"redact\",\"reidentify\"]}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("reidentify"), body);
        verify(apiKeyDataService, never()).updateScopes(anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("A key cannot change a key that holds a scope it does not")
    void cannotChangeAWiderKey() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope(), ApiKeyScope.REDACT.getScope()));
        final ApiKeyEntity wider = storedKey(callerUserId, Set.of("redact", "ledger:export"));

        final String body = setScopes(wider, "{\"scopes\":[\"redact\"]}")
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("ledger:export"), body);
        verify(apiKeyDataService, never()).updateScopes(anyString(), any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("A non-administrator gets a 404 for another user's key, as if it did not exist")
    void anotherUsersKeyIsNotFoundForANonAdministrator() throws Exception {
        callerIsAdministrator(false);
        final ApiKeyEntity theirs = storedKey(new ObjectId(), Set.of("redact"));

        setScopes(theirs, "{\"scopes\":[\"redact\"]}").andExpect(status().isNotFound());
        perform(delete("/api/api-keys/" + theirs.getId().toHexString())).andExpect(status().isNotFound());

        verify(apiKeyDataService, never()).updateScopes(anyString(), any(), any(), any(), anyString(), any());
        verify(apiKeyDataService, never()).deleteByApiKey(anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("An administrator changes another user's key, as that key's owner in the service")
    void anAdministratorChangesAnotherUsersKey() throws Exception {
        callerIsAdministrator(true);
        final ObjectId owner = new ObjectId();
        final ApiKeyEntity theirs = storedKey(owner, Set.of("redact", "policies:read"));
        when(apiKeyDataService.updateScopes(anyString(), eq(owner), eq(theirs), anySet(), anyString(), anyString()))
                .thenReturn(ServiceResponse.success());

        setScopes(theirs, "{\"scopes\":[\"redact\"]}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("A malformed or unknown key ID is a 404")
    void anUnknownKeyIsNotFound() throws Exception {
        perform(delete("/api/api-keys/not-an-id")).andExpect(status().isNotFound());
        perform(delete("/api/api-keys/" + new ObjectId().toHexString())).andExpect(status().isNotFound());
    }

    // ----- revoking -----

    @Test
    @DisplayName("A caller revokes one of its own keys, audited with the calling key")
    void revokesAnOwnKey() throws Exception {
        final ApiKeyEntity key = storedKey(callerUserId, Set.of("redact"));
        when(apiKeyDataService.deleteByApiKey(eq("req-keys"), eq(callerUserId), eq(key), eq("api"),
                contains("api_key: " + callerApiKeyId))).thenReturn(ServiceResponse.success());

        perform(delete("/api/api-keys/" + key.getId().toHexString())).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("The key making the request cannot revoke itself")
    void aKeyCannotRevokeItself() throws Exception {
        when(apiKeyDataService.findOneById(callerApiKeyId)).thenReturn(callerKey);

        final String body = perform(delete("/api/api-keys/" + callerApiKeyId.toHexString()))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("making the request"), body);
        verify(apiKeyDataService, never()).deleteByApiKey(anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("A key cannot revoke a key that holds a scope it does not")
    void cannotRevokeAWiderKey() throws Exception {
        callerKey = keyHolding(Set.of(ApiKeyScope.API_KEYS_WRITE.getScope()));
        final ApiKeyEntity wider = storedKey(callerUserId, Set.of("redact"));

        perform(delete("/api/api-keys/" + wider.getId().toHexString())).andExpect(status().isForbidden());

        verify(apiKeyDataService, never()).deleteByApiKey(anyString(), any(), any(), anyString(), any());
    }

}
