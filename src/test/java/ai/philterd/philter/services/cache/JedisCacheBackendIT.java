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

import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link JedisCacheBackend} against a real RESP server.
 *
 * <p>Every other cache test runs with a blank {@code CACHE_HOSTNAME}, which selects
 * {@link InMemoryCacheBackend}, so until this existed the Jedis path had no coverage at all. That path
 * is the one a clustered deployment uses: with more than one Philter node, the API key cache, the
 * context replacements, and the login lockout counters all have to be shared, and login lockout in
 * particular is a security control that silently weakens if each node counts separately.
 *
 * <p>The server is an in-process implementation of the Redis wire protocol (jedis-mock), the same
 * arrangement as {@code mongo-java-server} elsewhere in this suite: the production client talks the
 * real protocol to it, so a client upgrade that changed behavior would show up here, and the build
 * still needs no Docker and no external service.
 */
class JedisCacheBackendIT {

    private RedisServer server;
    private JedisCacheBackend backend;

    @BeforeEach
    void startServer() throws Exception {
        server = RedisServer.newRedisServer().start();
        backend = new JedisCacheBackend(server.getHost(), server.getBindPort(), "", false);
    }

    @AfterEach
    void stopServer() throws Exception {
        if (backend != null) {
            backend.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("A value written with a TTL can be read back")
    void setexThenGet() {
        backend.setex("philter:key", 60, "value");

        assertEquals("value", backend.get("philter:key"));
        assertTrue(backend.exists("philter:key"));
    }

    @Test
    @DisplayName("A missing key reads as null and does not exist")
    void missingKey() {
        assertNull(backend.get("philter:absent"));
        assertFalse(backend.exists("philter:absent"));
    }

    @Test
    @DisplayName("Deleting a key removes it")
    void delRemovesTheKey() {
        backend.setex("philter:key", 60, "value");

        backend.del("philter:key");

        assertNull(backend.get("philter:key"));
        assertFalse(backend.exists("philter:key"));
    }

    @Test
    @DisplayName("Hash fields round-trip independently of each other")
    void hashFields() {
        backend.hset("philter:hash", "first", "one");
        backend.hset("philter:hash", "second", "two");

        assertEquals("one", backend.hget("philter:hash", "first"));
        assertEquals("two", backend.hget("philter:hash", "second"));
        assertTrue(backend.hexists("philter:hash", "first"));
        assertFalse(backend.hexists("philter:hash", "third"));
        assertNull(backend.hget("philter:hash", "third"));
    }

    @Test
    @DisplayName("A hash can be given an expiry")
    void expireOnAHash() {
        backend.hset("philter:hash", "field", "value");

        backend.expire("philter:hash", 60);

        // The point is that the command is accepted and the data survives it; the clock is not
        // advanced here, so expiry itself is left to the server.
        assertEquals("value", backend.hget("philter:hash", "field"));
    }

    @Test
    @DisplayName("A cache backed by Jedis shares state, which is the reason it exists")
    void twoBackendsAgainstTheSameServerShareState() {
        final JedisCacheBackend other = new JedisCacheBackend(server.getHost(), server.getBindPort(), "", false);

        try {
            backend.setex("philter:shared", 60, "written-by-one");

            // Two Philter nodes pointed at the same Valkey must see each other's writes. This is the
            // property the in-memory backend cannot provide and the whole reason for this code path.
            assertEquals("written-by-one", other.get("philter:shared"));

            other.del("philter:shared");
            assertNull(backend.get("philter:shared"));

        } finally {
            other.close();
        }
    }

}
