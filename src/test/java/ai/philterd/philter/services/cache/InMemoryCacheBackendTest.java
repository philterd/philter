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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InMemoryCacheBackendTest {

    private final CacheBackend backend = InMemoryCacheBackend.INSTANCE;

    @Test
    public void stringSetGetExistsDelete() {
        final String key = "string-key-" + System.nanoTime();

        assertNull(backend.get(key));
        assertFalse(backend.exists(key));

        backend.setex(key, 60, "value");
        assertEquals("value", backend.get(key));
        assertTrue(backend.exists(key));

        backend.del(key);
        assertNull(backend.get(key));
        assertFalse(backend.exists(key));
    }

    @Test
    public void hashSetGetExistsDelete() {
        final String key = "hash-key-" + System.nanoTime();

        assertNull(backend.hget(key, "field"));
        assertFalse(backend.hexists(key, "field"));

        backend.hset(key, "field", "value");
        backend.expire(key, 60);

        assertEquals("value", backend.hget(key, "field"));
        assertTrue(backend.hexists(key, "field"));
        assertFalse(backend.hexists(key, "missing"));

        backend.del(key);
        assertNull(backend.hget(key, "field"));
        assertFalse(backend.hexists(key, "field"));
    }

}
