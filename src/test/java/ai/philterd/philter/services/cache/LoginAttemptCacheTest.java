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
package ai.philterd.philter.services.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the login lockout counting. With no cache host configured the cache uses the shared
 * in-memory backend; each test uses a unique username so the shared store does not bleed between
 * tests.
 */
class LoginAttemptCacheTest {

    private LoginAttemptCache cache;
    private String user;

    @BeforeEach
    void setUp() {
        // No host: uses the in-memory backend. Defaults: 5 attempts, 900s window.
        cache = new LoginAttemptCache("", 6379, "", false);
        user = "user-" + System.nanoTime() + "@example.com";
    }

    @Test
    void notLockedInitially() {
        assertFalse(cache.isLocked(user));
    }

    @Test
    void notLockedBelowThreshold() {
        for (int i = 0; i < cache.getMaxAttempts() - 1; i++) {
            cache.recordFailure(user);
        }
        assertFalse(cache.isLocked(user));
    }

    @Test
    void locksAtThreshold() {
        long count = 0;
        for (int i = 0; i < cache.getMaxAttempts(); i++) {
            count = cache.recordFailure(user);
        }
        assertEquals(cache.getMaxAttempts(), count);
        assertTrue(cache.isLocked(user));
    }

    @Test
    void resetClearsLock() {
        for (int i = 0; i < cache.getMaxAttempts(); i++) {
            cache.recordFailure(user);
        }
        assertTrue(cache.isLocked(user));

        cache.reset(user);

        assertFalse(cache.isLocked(user));
    }

    @Test
    void lockoutIsCaseInsensitive() {
        for (int i = 0; i < cache.getMaxAttempts(); i++) {
            cache.recordFailure(user.toUpperCase());
        }
        // A lock recorded under an upper-cased name applies to the lower-cased form too.
        assertTrue(cache.isLocked(user.toLowerCase()));
    }

    @Test
    void recordFailureReturnsRunningCount() {
        assertEquals(1, cache.recordFailure(user));
        assertEquals(2, cache.recordFailure(user));
        assertEquals(3, cache.recordFailure(user));
    }

}
