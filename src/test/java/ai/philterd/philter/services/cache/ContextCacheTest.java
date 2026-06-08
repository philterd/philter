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

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The context cache key is namespaced by the owning user's id so that two users with a same-named
 * context (for example the auto-created {@code default} context) never share a cache entry. A null
 * userId would produce an unnamespaced key that collapses every user's same-named context into one
 * shared entry, leaking one user's token mappings to another — so it must be rejected outright rather
 * than silently producing a cross-user key.
 *
 * <p>With no cache host configured the cache uses the shared in-memory backend.
 */
class ContextCacheTest {

    private ContextCache cache;

    @BeforeEach
    void setUp() {
        // No host: uses the in-memory backend.
        cache = new ContextCache("", 6379, "", false);
    }

    @Test
    void containsTokenRejectsNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> cache.containsToken(null, "default", "token"));
    }

    @Test
    void getReplacementRejectsNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> cache.getReplacement(null, "default", "token"));
    }

    @Test
    void setTokenReplacementRejectsNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> cache.setTokenReplacement(null, "default", "token", new ObjectId(), "replacement"));
    }

    @Test
    void deleteContextRejectsNullUserId() {
        assertThrows(IllegalArgumentException.class,
                () -> cache.deleteContext(null, "default"));
    }

    @Test
    void aRealUserIdIsAccepted() {
        // A genuine user id namespaces the key and must not throw.
        assertDoesNotThrow(() -> cache.containsToken(new ObjectId(), "default", "token"));
    }
}
