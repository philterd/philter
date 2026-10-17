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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Philter stores in Valkey can be neither read nor forged by something that can reach Valkey. */
class SealedCacheBackendTest {

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final byte[] masterKey = randomKey();
    private InMemoryCacheBackend store;
    private SealedCacheBackend sealed;

    private static byte[] randomKey() {
        final byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCacheBackend(now::get);
        sealed = new SealedCacheBackend(store, masterKey, now::get);
    }

    @Test
    @DisplayName("Values and hash fields read back as written")
    void roundTrips() {
        sealed.setex("apikey_abc", 60, "{\"userId\":\"u1\"}");
        assertEquals("{\"userId\":\"u1\"}", sealed.get("apikey_abc"));
        assertTrue(sealed.exists("apikey_abc"));

        sealed.hset("context_u1_cohort", "tokenhash", "entry|Jane Roe");
        assertEquals("entry|Jane Roe", sealed.hget("context_u1_cohort", "tokenhash"));
        assertTrue(sealed.hexists("context_u1_cohort", "tokenhash"));

        sealed.hdel("context_u1_cohort", "tokenhash");
        assertNull(sealed.hget("context_u1_cohort", "tokenhash"));
        sealed.del("apikey_abc");
        assertNull(sealed.get("apikey_abc"));
        assertFalse(sealed.exists("apikey_abc"));
    }

    @Test
    @DisplayName("Neither the names nor the values are stored readably")
    void nothingIsStoredReadably() {
        // Markers long enough that random ciphertext cannot contain them by chance. A two-letter one, such as a
        // user ID of u1, appears in the base64 of a random value about once in sixty runs.
        final String userId = "UserMarker7Qx2Lp9Wz4Kd";
        final String person = "PersonMarker3Hv8Ns5Rt1";
        sealed.setex("apikey_abc", 60, "{\"userId\":\"" + userId + "\"}");
        sealed.hset("context_u1_cohort", "tokenhash", "entry|" + person);

        assertNull(store.get("apikey_abc"), "the key name is not used as is");
        assertNull(store.hget("context_u1_cohort", "tokenhash"));

        final String storedValue = store.get(sealed.name("apikey_abc"));
        assertTrue(storedValue.startsWith("s1."), storedValue);
        assertNotReadable(storedValue, userId);
        assertNotReadable(store.hget(sealed.name("context_u1_cohort"), sealed.field("tokenhash")), person);
        assertFalse(sealed.name("context_u1_cohort").contains("cohort"));
    }

    /** Neither the stored text nor the bytes its base64 decodes to contain the marker, so it is encrypted, not encoded. */
    private static void assertNotReadable(final String stored, final String marker) {
        assertFalse(stored.contains(marker), stored);
        final byte[] decoded = Base64.getDecoder().decode(stored.substring("s1.".length()));
        assertFalse(new String(decoded, StandardCharsets.ISO_8859_1).contains(marker), stored);
    }

    @Test
    @DisplayName("A value written by anything without the key is ignored, as if absent")
    void aForgedValueIsIgnored() {
        // Readable JSON at the name Philter reads, as an attacker who knew the name would write it.
        store.setex(sealed.name("apikey_abc"), 60, "{\"userId\":\"admin\"}");
        assertNull(sealed.get("apikey_abc"));
        assertFalse(sealed.exists("apikey_abc"));

        store.hset(sealed.name("context_u1_cohort"), sealed.field("tokenhash"), "s1.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
        assertNull(sealed.hget("context_u1_cohort", "tokenhash"));
        assertFalse(sealed.hexists("context_u1_cohort", "tokenhash"));
    }

    @Test
    @DisplayName("A genuine value copied under another name is ignored")
    void aValueMovedToAnotherNameIsIgnored() {
        sealed.setex("apikey_real", 60, "{\"userId\":\"admin\"}");
        store.setex(sealed.name("apikey_chosen"), 60, store.get(sealed.name("apikey_real")));
        assertNull(sealed.get("apikey_chosen"));

        sealed.hset("context_u1_a", "token1", "entry|Jane Roe");
        store.hset(sealed.name("context_u1_a"), sealed.field("token2"),
                store.hget(sealed.name("context_u1_a"), sealed.field("token1")));
        assertNull(sealed.hget("context_u1_a", "token2"), "nor under another field");
    }

    @Test
    @DisplayName("A value written back after it expired is ignored")
    void anExpiredValueWrittenBackIsIgnored() {
        sealed.setex("apikey_revoked", 60, "{\"userId\":\"u1\"}");
        final String copy = store.get(sealed.name("apikey_revoked"));
        sealed.del("apikey_revoked");

        now.addAndGet(61_000L);
        store.setex(sealed.name("apikey_revoked"), 3600, copy);
        assertNull(sealed.get("apikey_revoked"));
    }

    @Test
    @DisplayName("Values written under another encryption key read as absent")
    void anotherKeysValuesReadAsAbsent() {
        sealed.setex("apikey_abc", 60, "{\"userId\":\"u1\"}");
        final SealedCacheBackend rotated = new SealedCacheBackend(store, randomKey(), now::get);
        assertNull(rotated.get("apikey_abc"));
    }

    @Test
    @DisplayName("Counters still count, under a hashed name")
    void countersCount() {
        assertEquals(1, sealed.incrementInWindow("signin_rate_10.0.0.1", 60));
        assertEquals(2, sealed.incrementInWindow("signin_rate_10.0.0.1", 60));
        assertNull(store.get("signin_rate_10.0.0.1"));
    }

    @Test
    @DisplayName("Whether the cache is on this machine is judged from its name, without a lookup")
    void loopbackIsJudgedFromTheName() {
        assertTrue(Cache.isLoopback("localhost"));
        assertTrue(Cache.isLoopback("127.0.0.1"));
        assertTrue(Cache.isLoopback("::1"));
        assertFalse(Cache.isLoopback("valkey"));
        assertFalse(Cache.isLoopback("127.example.com"));
        assertFalse(Cache.isLoopback("10.0.0.5"));
    }

    @Test
    @DisplayName("Valkey reached over a network without a password or TLS is warned about")
    void anUnprotectedCacheIsWarnedAbout() {
        assertTrue(Cache.unprotectedWarning("valkey", "", false).contains("without a password or TLS"));
        assertTrue(Cache.unprotectedWarning("valkey", "secret", false).contains("without TLS"));
        assertTrue(Cache.unprotectedWarning("valkey", null, true).contains("without a password"));
        assertNull(Cache.unprotectedWarning("valkey", "secret", true));
        assertNull(Cache.unprotectedWarning("localhost", "", false), "a cache on this machine is not");
    }

}
