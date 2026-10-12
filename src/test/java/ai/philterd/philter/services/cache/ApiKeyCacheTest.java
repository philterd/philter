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

import ai.philterd.philter.data.entities.ApiKeyEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiKeyCacheTest {

    @Test
    @DisplayName("The cache, which may be a shared Valkey or Redis server, never holds a session's client details")
    void aSessionsClientDetailsAreNotCached() {
        final ApiKeyCache cache = new ApiKeyCache("", 0, "", false);
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId("0123456789abcdef01234567");
        key.setApiKeyHash("hash");
        key.setClientAddress("203.0.113.7");
        key.setUserAgent("Mozilla/5.0 (Test)");

        cache.insert("hash", key);
        final ApiKeyEntity cached = cache.get("hash");

        assertEquals(key.getId(), cached.getId());
        assertNull(cached.getClientAddress());
        assertNull(cached.getUserAgent());
    }

}
