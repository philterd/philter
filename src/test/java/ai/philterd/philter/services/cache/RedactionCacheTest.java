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

import ai.philterd.philter.data.mongodb.MongoIds;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the in-process redaction cache round-trips policy JSON and redact lists. The cache uses the
 * shared in-memory backend, so each test uses a fresh user id to avoid bleeding between tests.
 */
class RedactionCacheTest {

    private final RedactionCache cache = new RedactionCache();

    @Test
    void policyRoundTripsAndIsScopedToUserAndName() {
        final String user = MongoIds.newId();

        // Nothing cached yet.
        assertNull(cache.getPolicy(user, "default"));

        cache.putPolicy(user, "default", "{\"identifiers\":{}}", 3);
        final RedactionCache.CachedPolicy cached = cache.getPolicy(user, "default");
        assertEquals("{\"identifiers\":{}}", cached.getPolicyJson());
        assertEquals(3, cached.getRevision());

        // A different policy name for the same user is a separate entry.
        assertNull(cache.getPolicy(user, "other"));
        // A different user does not see this user's cached policy.
        assertNull(cache.getPolicy(MongoIds.newId(), "default"));
    }

    @Test
    void redactListsRoundTripAndAreScopedToUser() {
        final String user = MongoIds.newId();

        assertNull(cache.getRedactLists(user));

        cache.putRedactLists(user, new RedactionCache.CachedRedactLists(List.of("alice", "bob"), List.of("acme"), 3, 5));

        final RedactionCache.CachedRedactLists cached = cache.getRedactLists(user);
        assertEquals(List.of("alice", "bob"), cached.getAlwaysRedact());
        assertEquals(List.of("acme"), cached.getNeverRedact());
        assertTrue(cached.isAt(3, 5));
        assertFalse(cached.isAt(4, 5), "a later always-redact revision is not this copy");
        assertFalse(cached.isAt(3, 6), "a later never-redact revision is not this copy");

        // A list write evicts the copy.
        cache.evictRedactLists(user);
        assertNull(cache.getRedactLists(user));

        // A different user has nothing cached.
        assertNull(cache.getRedactLists(MongoIds.newId()));
    }

    @Test
    void cachedRedactListsTreatNullListsAsEmpty() {
        final String user = MongoIds.newId();

        // A user with no saved terms is cached as empty lists, never null on read.
        cache.putRedactLists(user, new RedactionCache.CachedRedactLists(null, null, 0, 0));

        final RedactionCache.CachedRedactLists cached = cache.getRedactLists(user);
        assertEquals(List.of(), cached.getAlwaysRedact());
        assertEquals(List.of(), cached.getNeverRedact());
    }

}
