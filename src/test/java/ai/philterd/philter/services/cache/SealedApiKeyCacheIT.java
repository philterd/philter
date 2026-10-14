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
import ai.philterd.philter.data.mongodb.MongoIds;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.github.fppt.jedismock.RedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.security.SecureRandom;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Against a real RESP server: something that can write to Valkey cannot make Philter accept an API key it
 * never issued, by copying a cached record to the hash of a key of its own choosing.
 */
class SealedApiKeyCacheIT {

    private RedisServer server;
    private SealedCacheBackend backend;
    private ApiKeyCache cache;
    private Jedis attacker;

    @BeforeEach
    void start() throws Exception {
        server = RedisServer.newRedisServer().start();
        final byte[] masterKey = new byte[32];
        new SecureRandom().nextBytes(masterKey);
        backend = new SealedCacheBackend(new JedisCacheBackend(server.getHost(), server.getBindPort(), "", false),
                masterKey, System::currentTimeMillis);
        cache = new ApiKeyCache(backend);
        attacker = new Jedis(server.getHost(), server.getBindPort());
    }

    @AfterEach
    void stop() throws Exception {
        attacker.close();
        backend.close();
        server.stop();
    }

    private static ApiKeyEntity adminKey(final String userId) {
        final ApiKeyEntity entity = new ApiKeyEntity();
        entity.setId(MongoIds.newId());
        entity.setUserId(userId);
        entity.setApiKeyHash(EncryptionService.hashSha256("sk_adminadminadminadminadminadmin1"));
        return entity;
    }

    @Test
    @DisplayName("A cached record copied to the hash of another key is not accepted for that key")
    void aCopiedRecordIsNotAKey() {
        final String adminId = MongoIds.newId();
        final ApiKeyEntity admin = adminKey(adminId);
        cache.insert(admin.getApiKeyHash(), admin);
        assertEquals(adminId, cache.get(admin.getApiKeyHash()).getUserId());

        final String forgedHash = EncryptionService.hashSha256("sk_forgedforgedforgedforgedforged00");
        for (final String name : attacker.keys("*")) {
            // As before, under the name Philter used to read, and under every name Philter now writes.
            attacker.set("apikey_" + forgedHash, attacker.get(name));
            attacker.set(backend.name("apikey_" + forgedHash), attacker.get(name));
        }
        attacker.set("apikey_" + forgedHash, "{\"userId\":\"" + adminId + "\",\"apiKeyHash\":\"" + forgedHash + "\"}");

        assertNull(cache.get(forgedHash));
        assertFalse(cache.containsApiKey(forgedHash));
    }

    @Test
    @DisplayName("Valkey holds no readable key name, user ID, or record")
    void nothingReadableIsStored() {
        final String adminId = MongoIds.newId();
        final ApiKeyEntity admin = adminKey(adminId);
        cache.insert(admin.getApiKeyHash(), admin);

        final Set<String> names = attacker.keys("*");
        assertEquals(1, names.size());
        for (final String name : names) {
            assertFalse(name.contains("apikey"), name);
            assertFalse(name.contains(admin.getApiKeyHash()), name);
            assertFalse(attacker.get(name).contains(adminId), "the stored record names no user");
        }
    }

}
