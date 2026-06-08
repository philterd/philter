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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the in-process redaction cache round-trips policy JSON and redact lists. The cache uses the
 * shared in-memory backend, so each test uses a fresh user id to avoid bleeding between tests.
 */
class RedactionCacheTest {

    private final RedactionCache cache = new RedactionCache();

    @Test
    void policyJsonRoundTripsAndIsScopedToUserAndName() {
        final ObjectId user = new ObjectId();

        // Nothing cached yet.
        assertNull(cache.getPolicyJson(user, "default"));

        cache.putPolicyJson(user, "default", "{\"identifiers\":{}}");
        assertEquals("{\"identifiers\":{}}", cache.getPolicyJson(user, "default"));

        // A different policy name for the same user is a separate entry.
        assertNull(cache.getPolicyJson(user, "other"));
        // A different user does not see this user's cached policy.
        assertNull(cache.getPolicyJson(new ObjectId(), "default"));
    }

    @Test
    void redactListsRoundTripAndAreScopedToUser() {
        final ObjectId user = new ObjectId();

        assertNull(cache.getRedactLists(user));

        cache.putRedactLists(user, List.of("alice", "bob"), List.of("acme"));

        final RedactionCache.CachedRedactLists cached = cache.getRedactLists(user);
        assertEquals(List.of("alice", "bob"), cached.getAlwaysRedact());
        assertEquals(List.of("acme"), cached.getNeverRedact());

        // A different user has nothing cached.
        assertNull(cache.getRedactLists(new ObjectId()));
    }

    @Test
    void cachedRedactListsTreatNullListsAsEmpty() {
        final ObjectId user = new ObjectId();

        // A user with no saved terms is cached as empty lists, never null on read.
        cache.putRedactLists(user, null, null);

        final RedactionCache.CachedRedactLists cached = cache.getRedactLists(user);
        assertEquals(List.of(), cached.getAlwaysRedact());
        assertEquals(List.of(), cached.getNeverRedact());
    }

}
