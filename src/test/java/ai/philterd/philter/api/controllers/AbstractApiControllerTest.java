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

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        when(userService.findByEmail("ghost@example.com")).thenReturn(null);
        assertNull(controller.resolveTargetUserId(userService, callerId, "ghost@example.com"));
    }

    @Test
    void nonAdminNamingAnotherOwnerResolvesToNull() {
        final ObjectId otherId = new ObjectId();
        when(userService.findByEmail("other@example.com")).thenReturn(user(otherId, "other@example.com", "user"));
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "caller@example.com", "user"));

        assertNull(controller.resolveTargetUserId(userService, callerId, "other@example.com"));
    }

    @Test
    void adminNamingAnotherOwnerResolvesToThatOwner() {
        final ObjectId otherId = new ObjectId();
        when(userService.findByEmail("other@example.com")).thenReturn(user(otherId, "other@example.com", "user"));
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "admin@example.com", "admin"));

        assertEquals(otherId, controller.resolveTargetUserId(userService, callerId, "other@example.com"));
    }

    @Test
    void killSwitchDisablesAdminCrossUserAccess() {
        // With cross-user access disabled, even an admin naming another user is denied (resolves to null).
        controller.crossUserAccessEnabled = false;
        final ObjectId otherId = new ObjectId();
        when(userService.findByEmail("other@example.com")).thenReturn(user(otherId, "other@example.com", "user"));
        when(userService.findOneById(callerId)).thenReturn(user(callerId, "admin@example.com", "admin"));

        assertNull(controller.resolveTargetUserId(userService, callerId, "other@example.com"));
    }

    @Test
    void killSwitchDoesNotAffectOwnAccess() {
        // The kill switch only blocks reaching OTHER users; own resources remain accessible.
        controller.crossUserAccessEnabled = false;
        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, null));
        when(userService.findByEmail("caller@example.com")).thenReturn(user(callerId, "caller@example.com", "admin"));
        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, "caller@example.com"));
    }

    @Test
    void nonAdminNamingThemselvesResolvesToOwnId() {
        // Naming your own email is allowed without admin rights.
        when(userService.findByEmail("caller@example.com")).thenReturn(user(callerId, "caller@example.com", "user"));

        assertEquals(callerId, controller.resolveTargetUserId(userService, callerId, "caller@example.com"));
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
