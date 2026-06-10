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

import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the shared admin-access helper used by every API controller: a regular user is confined to
 * their own resources, while an admin may target another user via the {@code owner} email.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbstractApiControllerTest {

    /** Minimal concrete subclass so the protected helpers can be exercised directly. */
    private static final class TestController extends AbstractApiController {
        private boolean crossUserAccessEnabled = true;
        TestController(final ApiKeyDataService apiKeyService, final ApiKeyCache apiKeyCache) {
            super(apiKeyService, apiKeyCache);
        }
        @Override
        protected boolean isCrossUserAccessEnabled() {
            return crossUserAccessEnabled;
        }
    }

    @Mock private UserService userService;
    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;

    private TestController controller;
    private ObjectId callerId;

    @BeforeEach
    void setUp() {
        controller = new TestController(apiKeyDataService, apiKeyCache);
        callerId = new ObjectId();
    }

    private UserEntity user(final ObjectId id, final String email, final String role) {
        final UserEntity user = new UserEntity();
        user.setId(id);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }

    @Test
    void blankOwnerResolvesToCaller() {
        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, null));
        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, "   "));
    }

    @Test
    void unknownOwnerResolvesToNull() {
        when(userService.findByUsername("ghost@example.com")).thenReturn(null);
        assertNull(controller.resolveTargetUserId(userService, callerId, "ghost@example.com"));
    }

    @Test
    void nonAdminNamingAnotherOwnerResolvesToNull() {
        final ObjectId otherId = new ObjectId();
        when(userService.findByUsername("other@example.com")).thenReturn(user(otherId, "other@example.com", "user"));
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "caller@example.com", "user"));

        assertNull(controller.resolveTargetUserId(userService, callerId, "other@example.com"));
    }

    @Test
    void adminNamingAnotherOwnerResolvesToThatOwner() {
        final ObjectId otherId = new ObjectId();
        when(userService.findByUsername("other@example.com")).thenReturn(user(otherId, "other@example.com", "user"));
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "admin@example.com", "admin"));

        assertEquals(otherId, controller.resolveTargetUserId(userService, callerId, "other@example.com"));
    }

    @Test
    void killSwitchDisablesAdminCrossUserAccess() {
        // With cross-user access disabled, even an admin naming another user is denied (resolves to null).
        controller.crossUserAccessEnabled = false;
        final ObjectId otherId = new ObjectId();
        when(userService.findByUsername("other@example.com")).thenReturn(user(otherId, "other@example.com", "user"));
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "admin@example.com", "admin"));

        assertNull(controller.resolveTargetUserId(userService, callerId, "other@example.com"));
    }

    @Test
    void killSwitchDoesNotAffectOwnAccess() {
        // The kill switch only blocks reaching OTHER users; own resources remain accessible.
        controller.crossUserAccessEnabled = false;
        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, null));
        when(userService.findByUsername("caller@example.com")).thenReturn(user(callerId, "caller@example.com", "admin"));
        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, "caller@example.com"));
    }

    @Test
    void nonAdminNamingThemselvesResolvesToOwnId() {
        // Naming your own email is allowed without admin rights.
        when(userService.findByUsername("caller@example.com")).thenReturn(user(callerId, "caller@example.com", "user"));

        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, "caller@example.com"));
    }

    // ----- getApiKeyEntity verifies the filter-stashed identity against the request's credential -----

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** Binds a request to the current thread with the given API key entity stashed as the auth attribute. */
    private void bindRequestWithStashedKey(final ApiKeyEntity stashed) {
        final MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, stashed);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ApiKeyEntity apiKeyEntity(final String apiKey, final ObjectId userId) {
        final ApiKeyEntity entity = new ApiKeyEntity();
        entity.setUserId(userId);
        entity.setApiKeyHash(EncryptionService.hashSha256(apiKey));
        return entity;
    }

    @Test
    void reusesStashedKeyWhenItMatchesTheRequestCredential() {
        final ApiKeyEntity stashed = apiKeyEntity("sk_match", new ObjectId());
        bindRequestWithStashedKey(stashed);

        final ApiKeyEntity resolved = controller.getApiKeyEntity("Bearer sk_match");

        // The stashed entity matches this request's credential, so it is reused as-is...
        assertSame(stashed, resolved);
        // ...without any cache or database lookup.
        verify(apiKeyCache, never()).containsApiKey(anyString());
        verify(apiKeyDataService, never()).findOneByApiKey(anyString());
    }

    @Test
    void doesNotTrustStashedKeyThatDoesNotMatchTheRequestCredential() {
        // A stash that belongs to a DIFFERENT credential (e.g. a stale/mis-bound request attribute).
        final ApiKeyEntity wrongStash = apiKeyEntity("sk_other", new ObjectId());
        bindRequestWithStashedKey(wrongStash);

        // The credential actually presented on this request resolves (freshly) to a different entity.
        final ApiKeyEntity correct = apiKeyEntity("sk_match", new ObjectId());
        when(apiKeyCache.containsApiKey(EncryptionService.hashSha256("sk_match"))).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey("sk_match")).thenReturn(correct);

        final ApiKeyEntity resolved = controller.getApiKeyEntity("Bearer sk_match");

        // The mismatched stash must NOT be trusted; the request's own credential is resolved instead.
        assertSame(correct, resolved);
        assertFalse(resolved == wrongStash);
        verify(apiKeyDataService).findOneByApiKey("sk_match");
    }

    @Test
    void resolvesFromCacheOrDbWhenNoStashIsPresent() {
        // No request bound / no stashed attribute (e.g. a caller that bypassed the filter).
        final ApiKeyEntity correct = apiKeyEntity("sk_match", new ObjectId());
        when(apiKeyCache.containsApiKey(EncryptionService.hashSha256("sk_match"))).thenReturn(false);
        when(apiKeyDataService.findOneByApiKey("sk_match")).thenReturn(correct);

        final ApiKeyEntity resolved = controller.getApiKeyEntity("Bearer sk_match");

        assertSame(correct, resolved);
    }

    @Test
    void rejectsMissingOrMalformedAuthorizationHeader() {
        assertNull(controller.getApiKeyEntity(null));
        assertNull(controller.getApiKeyEntity("not-bearer"));
        verify(apiKeyDataService, never()).findOneByApiKey(any());
    }

    @Test
    void isAdminReflectsRole() {
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "a@example.com", "admin"));
        assertTrue(controller.isAdmin(userService, callerId));

        final ObjectId plainId = new ObjectId();
        when(userService.findOneById(plainId)).thenReturn(user(plainId, "u@example.com", "user"));
        assertFalse(controller.isAdmin(userService, plainId));

        final ObjectId missingId = new ObjectId();
        when(userService.findOneById(missingId)).thenReturn(null);
        assertFalse(controller.isAdmin(userService, missingId));
    }

}
