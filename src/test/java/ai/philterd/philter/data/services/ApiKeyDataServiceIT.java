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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.model.ApiKeyScope;
import java.util.Set;
import ai.philterd.philter.model.AuditLogEvent;
import org.mockito.ArgumentCaptor;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link ApiKeyDataService} against a real (in-memory) MongoDB. These exercise
 * the create/find lifecycle, hash-based lookup, user scoping, counting, paging, and the soft-delete
 * behavior (keys are marked deleted, not removed) end to end — behavior the mock-based unit tests can
 * only approximate.
 */
class ApiKeyDataServiceIT extends AbstractMongoIT {

    private ApiKeyDataService service;
    private ai.philterd.philter.services.cache.ApiKeyCache apiKeyCache;
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void setUpService() {
        apiKeyCache = new ai.philterd.philter.services.cache.ApiKeyCache("", 0, "", false);
        // Held in a field rather than inlined so the audit events it receives can be verified.
        auditEventPublisher = mock(AuditEventPublisher.class);
        service = new ApiKeyDataService(mongoClient, auditEventPublisher, apiKeyCache);
    }

    @Test
    void deleteByApiKeyEvictsFromCache() {
        final ObjectId user = new ObjectId();
        final String apiKey = service.createApiKey("req", user, "src").getMessage();
        final ApiKeyEntity entity = service.findOneByApiKey(apiKey);

        // Simulate the key having been cached during authentication (the cache is keyed by hash).
        apiKeyCache.insert(entity.getApiKeyHash(), entity);
        assertTrue(apiKeyCache.containsApiKey(entity.getApiKeyHash()));

        service.deleteByApiKey("req", entity.getUserId(), entity, "src");

        // Deleting the key evicts it from the cache so it stops working immediately, not after the TTL.
        assertFalse(apiKeyCache.containsApiKey(entity.getApiKeyHash()));
    }

    @Test
    void createThenFindByApiKey() {
        final ObjectId user = new ObjectId();
        final ServiceResponse response = service.createApiKey("req", user, "src");

        assertTrue(response.isSuccessful());
        final String apiKey = response.getMessage();
        assertNotNull(apiKey);
        assertTrue(apiKey.startsWith("sk_"));

        final ApiKeyEntity found = service.findOneByApiKey(apiKey);
        assertNotNull(found);
        assertEquals(user, found.getUserId());
        assertFalse(found.isDeleted());
        assertNotNull(found.getId());
        assertNotNull(found.getTimestamp());
        // The stored prefix is the first 12 characters of the key followed by an ellipsis.
        assertEquals(apiKey.substring(0, 12) + "...", found.getApiKeyPrefix());
    }

    @Test
    void findOneByApiKeyReturnsNullForUnknownKey() {
        assertNull(service.findOneByApiKey("sk_does_not_exist"));
    }

    @Test
    void findAllAndCountAreScopedByUser() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.createApiKey("req", userA, "src");
        service.createApiKey("req", userA, "src");
        service.createApiKey("req", userB, "src");

        assertEquals(2, service.count(userA));
        assertEquals(1, service.count(userB));

        final List<ApiKeyEntity> userAKeys = service.findAll(userA, 0, 10);
        assertEquals(2, userAKeys.size());
        for (final ApiKeyEntity key : userAKeys) {
            assertEquals(userA, key.getUserId());
        }

        // A null userId counts/returns all keys across users.
        assertEquals(3, service.count(null));
        assertEquals(3, service.findAll(null, 0, 10).size());
    }

    @Test
    void findAllSupportsPaging() {
        final ObjectId user = new ObjectId();
        for (int i = 0; i < 5; i++) {
            service.createApiKey("req", user, "src");
        }

        assertEquals(5, service.count(user));
        assertEquals(2, service.findAll(user, 0, 2).size());
        assertEquals(2, service.findAll(user, 2, 2).size());
        assertEquals(1, service.findAll(user, 4, 2).size());
    }

    @Test
    void deleteByApiKeySoftDeletesAndHidesFromLookup() {
        final ObjectId user = new ObjectId();
        final String apiKey = service.createApiKey("req", user, "src").getMessage();

        final ApiKeyEntity entity = service.findOneByApiKey(apiKey);
        final ObjectId keyId = entity.getId();
        assertTrue(service.deleteByApiKey("req", entity.getUserId(), entity, "src").isSuccessful());
        assertTrue(entity.isDeleted());
        // The deletion time is stamped.
        assertNotNull(entity.getDeletedAt());

        // A soft-deleted key is revoked: no longer found, counted, or listed by default.
        assertNull(service.findOneByApiKey(apiKey));
        assertEquals(0, service.count(user));
        assertTrue(service.findAll(user, 0, 10).isEmpty());

        // ...but the record is retained so audit entries referencing the key id still resolve to it.
        final List<ApiKeyEntity> includingDeleted = service.findAll(user, 0, 10, true);
        assertEquals(1, includingDeleted.size());
        assertEquals(1, service.count(user, true));
        final ApiKeyEntity retained = includingDeleted.get(0);
        assertEquals(keyId, retained.getId());
        assertTrue(retained.isDeleted());
        assertNotNull(retained.getDeletedAt());
    }

    @Test
    void deletedKeyCanNeverAuthenticateAgain() {
        final ObjectId user = new ObjectId();
        final String apiKey = service.createApiKey("req", user, "src").getMessage();
        final ApiKeyEntity entity = service.findOneByApiKey(apiKey);

        service.deleteByApiKey("req", entity.getUserId(), entity, "src");

        // There is no reactivation: the revoked key stays unresolvable for authentication even though
        // its record is retained.
        assertNull(service.findOneByApiKey(apiKey));
    }

    private void setSessionTimes(final ObjectId keyId, final java.util.Date expiresAt, final java.util.Date idleExpiresAt) {
        mongoClient.getDatabase("philter").getCollection("api_keys").updateOne(new org.bson.Document("_id", keyId),
                new org.bson.Document("$set", new org.bson.Document("expires_at", expiresAt).append("idle_expires_at", idleExpiresAt)));
    }

    /** The stored document, read directly, so a field that was removed is seen to be gone. */
    private org.bson.Document storedKey(final ObjectId keyId) {
        return mongoClient.getDatabase("philter").getCollection("api_keys").find(new org.bson.Document("_id", keyId)).first();
    }

    private ApiKeyEntity signedIn(final ObjectId user) {
        final ApiKeyEntity issued = service.createSessionKey("req", user, Set.of("redact"), false, false, "api", null,
                "203.0.113.7", "Mozilla/5.0 (Test)");
        return service.findOneByApiKey(issued.getApiKey());
    }

    @Test
    void aSessionKeepsItsClientDetailsOnlyWhileItLasts() {
        final ObjectId user = new ObjectId();

        final ApiKeyEntity live = signedIn(user);
        assertEquals("203.0.113.7", live.getClientAddress());
        assertEquals("Mozilla/5.0 (Test)", live.getUserAgent());

        // The authentication cache, which may be a shared Valkey/Redis server, never holds them.
        apiKeyCache.insert(live.getApiKeyHash(), live);
        final ApiKeyEntity cached = apiKeyCache.get(live.getApiKeyHash());
        assertEquals(live.getId(), cached.getId());
        assertNull(cached.getClientAddress());
        assertNull(cached.getUserAgent());

        // Revoked one at a time.
        final ApiKeyEntity revoked = signedIn(user);
        assertTrue(service.deleteByApiKey("req", user, revoked, "api").isSuccessful());

        // Expired by the sweep.
        final ApiKeyEntity expired = signedIn(user);
        final java.util.Date past = new java.util.Date(System.currentTimeMillis() - 1000);
        setSessionTimes(expired.getId(), past, past);
        assertEquals(1L, service.expireSessionKeys(null));

        // Every other session, keeping the current one.
        final ApiKeyEntity other = signedIn(user);
        assertEquals(1L, service.revokeSessionKeys("req", user, "api", "reason: test", live.getId()));

        for (final ApiKeyEntity ended : List.of(revoked, expired, other)) {
            final org.bson.Document stored = storedKey(ended.getId());
            assertTrue(stored.getBoolean("deleted"), "the key ended");
            assertFalse(stored.containsKey("client_address"), "an ended session keeps no address: " + stored.toJson());
            assertFalse(stored.containsKey("user_agent"), "an ended session keeps no user agent: " + stored.toJson());
        }

        final org.bson.Document kept = storedKey(live.getId());
        assertFalse(kept.getBoolean("deleted"), "the session kept is still live");
        assertEquals("203.0.113.7", kept.getString("client_address"));

        // Every key of the user, as when the user is deactivated for good.
        service.deleteAllByUserId("req", user, "api");
        assertFalse(storedKey(live.getId()).containsKey("client_address"));
        assertFalse(storedKey(live.getId()).containsKey("user_agent"));
    }

    @Test
    void createSessionKeyRecordsItsLimitsFromTheDefaults() {
        final ObjectId user = new ObjectId();
        final ApiKeyEntity issued = service.createSessionKey("req", user, Set.of("redact"), "api", null);

        final ApiKeyEntity stored = service.findOneByApiKey(issued.getApiKey());
        assertTrue(stored.isSession());
        assertEquals(30 * 60, stored.getIdleTimeoutSeconds());
        final long lifetime = stored.getExpiresAt().getTime() - stored.getTimestamp().getTime();
        assertEquals(720L * 60_000, lifetime, "a 12-hour maximum lifetime by default");
        assertEquals(stored.getTimestamp().getTime() + 30 * 60_000L, stored.getIdleExpiresAt().getTime());
        verify(auditEventPublisher).auditEvent(eq("req"), eq(AuditLogEvent.API_KEY_CREATED), eq(stored.getId()), eq(user),
                eq("api"), eq("session: true"));
    }

    @Test
    void aRequestMovesTheIdleWindowButNeverPastTheLifetime() throws Exception {
        final ApiKeyEntity issued = service.createSessionKey("req", new ObjectId(), Set.of("redact"), "api", null);
        final ApiKeyEntity stored = service.findOneByApiKey(issued.getApiKey());
        final java.util.Date before = stored.getIdleExpiresAt();

        Thread.sleep(5);
        assertTrue(service.touchSessionKey(stored));
        assertTrue(service.findOneByApiKey(issued.getApiKey()).getIdleExpiresAt().after(before));

        // A minute of lifetime left: the idle window stops there rather than running its full 30 minutes.
        final java.util.Date soon = new java.util.Date(System.currentTimeMillis() + 60_000);
        setSessionTimes(stored.getId(), soon, soon);
        final ApiKeyEntity nearEnd = service.findOneByApiKey(issued.getApiKey());
        assertTrue(service.touchSessionKey(nearEnd));
        assertEquals(soon, service.findOneByApiKey(issued.getApiKey()).getIdleExpiresAt());
    }

    @Test
    void anIdleOrOutlivedSessionKeyIsRejectedAndExpiredOnce() {
        final ObjectId user = new ObjectId();
        final ApiKeyEntity idle = service.findOneByApiKey(service.createSessionKey("req", user, Set.of("redact"), "api", null).getApiKey());
        final ApiKeyEntity outlived = service.findOneByApiKey(service.createSessionKey("req", user, Set.of("redact"), "api", null).getApiKey());
        final String longLived = service.createApiKey("req", user, "src").getMessage();
        final java.util.Date past = new java.util.Date(System.currentTimeMillis() - 1000);
        final java.util.Date future = new java.util.Date(System.currentTimeMillis() + 3_600_000);
        setSessionTimes(idle.getId(), future, past);
        setSessionTimes(outlived.getId(), past, past);
        apiKeyCache.insert(idle.getApiKeyHash(), idle);

        assertFalse(service.touchSessionKey(idle), "past its idle window");
        assertFalse(service.touchSessionKey(outlived), "past its lifetime");

        assertEquals(2L, service.expireSessionKeys(null));
        assertEquals(0L, service.expireSessionKeys(null), "a second sweep, as another node would run, records nothing");

        assertTrue(service.findAll(user, 0, 10, true).stream().filter(ApiKeyEntity::isSession).allMatch(ApiKeyEntity::isDeleted));
        assertNotNull(service.findOneByApiKey(longLived), "a long-lived key never expires");
        assertFalse(apiKeyCache.containsApiKey(idle.getApiKeyHash()), "an expired key is evicted");
        verify(auditEventPublisher).auditEvent(any(), eq(AuditLogEvent.API_KEY_EXPIRED), eq(idle.getId()), eq(user),
                eq("system"), eq("reason: idle timeout"));
        verify(auditEventPublisher).auditEvent(any(), eq(AuditLogEvent.API_KEY_EXPIRED), eq(outlived.getId()), eq(user),
                eq("system"), eq("reason: maximum lifetime reached"));
    }

    @Test
    void theSweepDoesNotExpireAKeyRefreshedBetweenItsReadAndItsClaim() {
        final com.mongodb.client.MongoCollection<org.bson.Document> stored =
                mongoClient.getDatabase("philter").getCollection("api_keys");
        final com.mongodb.client.MongoCollection<org.bson.Document> racing = org.mockito.Mockito.spy(stored);
        final com.mongodb.client.MongoClient client = org.mockito.Mockito.mock(com.mongodb.client.MongoClient.class);
        final com.mongodb.client.MongoDatabase database = org.mockito.Mockito.mock(com.mongodb.client.MongoDatabase.class);
        org.mockito.Mockito.when(client.getDatabase("philter")).thenReturn(database);
        org.mockito.Mockito.when(database.getCollection("api_keys")).thenReturn(racing);
        final ApiKeyDataService sweeper = new ApiKeyDataService(client, auditEventPublisher, apiKeyCache);

        final String plaintext = service.createSessionKey("req", new ObjectId(), Set.of("redact"), "api", null).getApiKey();
        final ApiKeyEntity key = service.findOneByApiKey(plaintext);
        final java.util.Date future = new java.util.Date(System.currentTimeMillis() + 3_600_000);
        setSessionTimes(key.getId(), future, new java.util.Date(System.currentTimeMillis() - 1000));

        // A request on another node, whose clock runs behind, refreshes the key after the sweep has read
        // it as idle and before the sweep claims it.
        org.mockito.Mockito.doAnswer(invocation -> {
            stored.updateOne(new org.bson.Document("_id", key.getId()),
                    new org.bson.Document("$set", new org.bson.Document("idle_expires_at", future)));
            return invocation.callRealMethod();
        }).when(racing).updateOne(any(org.bson.conversions.Bson.class), any(org.bson.conversions.Bson.class));

        assertEquals(0L, sweeper.expireSessionKeys(null), "the refreshed key is no longer due");
        assertNotNull(service.findOneByApiKey(plaintext), "and is still valid");
        verify(auditEventPublisher, never()).auditEvent(any(), eq(AuditLogEvent.API_KEY_EXPIRED), any(), any(), any(), any());
    }

    @Test
    void revokeSessionKeysRevokesOnlyThatUsersSessionKeysAndEvictsThem() {
        final ObjectId user = new ObjectId();
        final ObjectId other = new ObjectId();
        final String session = service.createApiKey("req", user, "src").getMessage();
        final String longLived = service.createApiKey("req", user, "src").getMessage();
        final String othersSession = service.createApiKey("req", other, "src").getMessage();
        for (final String key : List.of(session, othersSession)) {
            mongoClient.getDatabase("philter").getCollection("api_keys").updateOne(
                    new org.bson.Document("_id", service.findOneByApiKey(key).getId()),
                    new org.bson.Document("$set", new org.bson.Document("session", true)));
        }
        final ApiKeyEntity sessionEntity = service.findOneByApiKey(session);
        apiKeyCache.insert(sessionEntity.getApiKeyHash(), sessionEntity);

        assertEquals(1L, service.revokeSessionKeys("req", user, "api", "reason: test"));

        assertTrue(service.findAll(user, 0, 10, true).stream()
                .filter(ApiKeyEntity::isSession).allMatch(ApiKeyEntity::isDeleted));
        assertNotNull(service.findOneByApiKey(longLived), "a long-lived key keeps working");
        assertNotNull(service.findOneByApiKey(othersSession), "another user's session key keeps working");
        assertFalse(apiKeyCache.containsApiKey(sessionEntity.getApiKeyHash()), "evicted, so it stops at once");
        verify(auditEventPublisher).auditEvent(eq("req"), eq(AuditLogEvent.API_KEY_DELETED), eq(sessionEntity.getId()),
                eq(sessionEntity.getId()), eq("api"), eq("reason: test"));

        assertEquals(0L, service.revokeSessionKeys("req", user, "api", "reason: test"), "nothing left to revoke");
    }

    @Test
    void deleteAllByUserIdStampsDeletedAtAndRetainsRecords() {
        final ObjectId user = new ObjectId();
        service.createApiKey("req", user, "src");
        service.createApiKey("req", user, "src");

        assertEquals(2L, service.deleteAllByUserId("req", user, "src"));

        // Both keys are revoked (hidden by default) but retained and stamped with a deletion time.
        assertEquals(0, service.count(user));
        final List<ApiKeyEntity> retained = service.findAll(user, 0, 10, true);
        assertEquals(2, retained.size());
        for (final ApiKeyEntity key : retained) {
            assertTrue(key.isDeleted());
            assertNotNull(key.getDeletedAt());
        }
    }

    @Test
    void deleteAllByUserIdScopesToOwningUser() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.createApiKey("req", userA, "src");
        service.createApiKey("req", userA, "src");
        service.createApiKey("req", userB, "src");

        assertEquals(2L, service.deleteAllByUserId("req", userA, "src"));

        assertEquals(0, service.count(userA));
        // Another user's keys are untouched.
        assertEquals(1, service.count(userB));
    }

    @Test
    void ensureApiKeyCreatesAndIsFindable() {
        final ObjectId user = new ObjectId();
        final String key = "sk_" + "a".repeat(32);

        assertTrue(service.ensureApiKey("req", user, key, "src"), "first call creates the key");

        final ApiKeyEntity entity = service.findOneByApiKey(key);
        assertNotNull(entity);
        assertEquals(user, entity.getUserId());
        assertEquals(1, service.count(user));
    }

    @Test
    void ensureApiKeyIsIdempotent() {
        final ObjectId user = new ObjectId();
        final String key = "sk_" + "b".repeat(32);

        assertTrue(service.ensureApiKey("req", user, key, "src"));
        assertFalse(service.ensureApiKey("req", user, key, "src"), "second call is a no-op");

        assertEquals(1, service.count(user), "the key is not duplicated");
    }

    @Test
    void ensureApiKeyDoesNotResurrectADeletedKey() {
        final ObjectId user = new ObjectId();
        final String key = "sk_" + "c".repeat(32);

        service.ensureApiKey("req", user, key, "src");
        service.deleteByApiKey("req", user, service.findOneByApiKey(key), "src");

        // A revoked bootstrap key must stay revoked across restarts.
        assertFalse(service.ensureApiKey("req", user, key, "src"));
        assertNull(service.findOneByApiKey(key));
    }

    @Test
    void ensureApiKeyMarksTheKeyAsBootstrapAndIsFindable() {
        final ObjectId user = new ObjectId();
        final String key = "sk_" + "d".repeat(32);

        service.ensureApiKey("req", user, key, "src");

        final ApiKeyEntity bootstrap = service.findActiveBootstrapKey(user);
        assertNotNull(bootstrap);
        assertTrue(bootstrap.isBootstrap());

        // A regular key is not reported as the bootstrap key.
        final ObjectId other = new ObjectId();
        service.createApiKey("req", other, "src");
        assertNull(service.findActiveBootstrapKey(other));
    }

    @Test
    void findActiveBootstrapKeyIgnoresADeletedBootstrapKey() {
        final ObjectId user = new ObjectId();
        final String key = "sk_" + "e".repeat(32);

        service.ensureApiKey("req", user, key, "src");
        service.deleteByApiKey("req", user, service.findOneByApiKey(key), "src");

        assertNull(service.findActiveBootstrapKey(user));
    }

    @Test
    void createdKeyPersistsItsScopesAndRoundTrips() {
        final ObjectId user = new ObjectId();
        final Set<String> scopes = Set.of(ApiKeyScope.REDACT.getScope(), ApiKeyScope.LEDGER_READ.getScope());

        final ServiceResponse response = service.createApiKey("req", user, "src", scopes);
        assertTrue(response.isSuccessful());

        final ApiKeyEntity found = service.findOneByApiKey(response.getMessage());
        assertNotNull(found);
        assertEquals(scopes, found.getScopes(), "scopes must survive the round-trip through MongoDB");
        assertTrue(found.hasScope(ApiKeyScope.REDACT));
        assertTrue(found.hasScope(ApiKeyScope.LEDGER_READ));
        assertFalse(found.hasScope(ApiKeyScope.LEDGER_EXPORT), "an unlisted scope must not be granted");
    }

    @Test
    void createWithoutScopesGrantsThemAll() {
        final ObjectId user = new ObjectId();

        final ServiceResponse response = service.createApiKey("req", user, "src");

        final ApiKeyEntity found = service.findOneByApiKey(response.getMessage());
        assertEquals(ApiKeyScope.all(), found.getScopes(),
                "the convenience overload provisions a fully-privileged key");
    }

    @Test
    void updateScopesReplacesThemAndKeepsTheKeyUsable() {
        final ObjectId user = new ObjectId();
        final ServiceResponse created = service.createApiKey("req", user, "src",
                Set.of(ApiKeyScope.REDACT.getScope()));
        final String apiKey = created.getMessage();
        final ApiKeyEntity entity = service.findOneByApiKey(apiKey);

        service.updateScopes("req", entity.getUserId(), entity, Set.of(ApiKeyScope.POLICIES_READ.getScope()), "src");

        // The credential is unchanged, so integrations keep working; only the scopes differ.
        final ApiKeyEntity reloaded = service.findOneByApiKey(apiKey);
        assertNotNull(reloaded, "the key itself must still resolve after a scope change");
        assertEquals(Set.of(ApiKeyScope.POLICIES_READ.getScope()), reloaded.getScopes());
        assertFalse(reloaded.hasScope(ApiKeyScope.REDACT), "the previous scope must be gone");
    }

    @Test
    void aKeyWithNoScopesGrantsNothing() {
        final ObjectId user = new ObjectId();
        final ServiceResponse created = service.createApiKey("req", user, "src", Set.of());

        final ApiKeyEntity found = service.findOneByApiKey(created.getMessage());
        assertTrue(found.getScopes().isEmpty());
        for (final ApiKeyScope scope : ApiKeyScope.values()) {
            assertFalse(found.hasScope(scope), scope + " must not be granted by an empty scope set");
        }
    }

    @Test
    void changingScopesRecordsASecurityAuditEventNamingTheChange() {
        final ObjectId user = new ObjectId();
        final ServiceResponse created = service.createApiKey("req", user, "src",
                Set.of(ApiKeyScope.REDACT.getScope()));
        final ApiKeyEntity entity = service.findOneByApiKey(created.getMessage());

        service.updateScopes("req-scope-change", entity.getUserId(), entity,
                Set.of(ApiKeyScope.POLICIES_READ.getScope()), "api");

        final ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditEventPublisher).auditEvent(eq("req-scope-change"),
                eq(AuditLogEvent.API_KEY_SCOPES_CHANGED), eq(entity.getId()), eq(entity.getId()),
                eq("api"), details.capture());

        // The entry must say what the key held before and after, so an auditor can tell whether the
        // key was widened or narrowed rather than only that something changed.
        assertTrue(details.getValue().contains("redact"), "must record the previous scopes: " + details.getValue());
        assertTrue(details.getValue().contains("policies:read"), "must record the new scopes: " + details.getValue());
    }

    @Test
    void theScopeChangeEventIsASecurityEventAndCannotBeSwitchedOff() {
        // Security events are always recorded; only REDACTION_ACTIVITY events honor
        // AUDIT_REDACTION_EVENTS_ENABLED. A key's permissions changing is exactly what an auditor asks for.
        assertEquals(AuditLogEvent.Category.SECURITY, AuditLogEvent.API_KEY_SCOPES_CHANGED.getCategory());
    }

    @Test
    void theBootstrapKeyIsUsableBecauseItCarriesEveryScope() {
        final ObjectId user = new ObjectId();
        final String bootstrapKey = "sk_abcdefghijklmnopqrstuvwxyz012345";

        assertTrue(service.ensureApiKey("req", user, bootstrapKey, "system"));

        // A bootstrap key exists to provision automation before anyone has chosen scopes. Seeding it
        // without any would leave every turnkey deployment with a credential that can call nothing.
        final ApiKeyEntity found = service.findOneByApiKey(bootstrapKey);
        assertNotNull(found);
        assertEquals(ApiKeyScope.all(), found.getScopes(), "the bootstrap key must carry every scope");
        for (final ApiKeyScope scope : ApiKeyScope.values()) {
            assertTrue(found.hasScope(scope), scope + " must be granted to the bootstrap key");
        }
    }

    @Test
    void scopesSurviveTheApiKeyCacheRoundTrip() {
        final ObjectId user = new ObjectId();
        final Set<String> scopes = Set.of(ApiKeyScope.REDACT.getScope(), ApiKeyScope.LEDGER_READ.getScope());
        final ServiceResponse created = service.createApiKey("req", user, "src", scopes);
        final String apiKey = created.getMessage();

        // First lookup populates the cache from MongoDB; the second is served from it. The cache
        // serializes the entity, so a serialization slip here would silently change what a key may do
        // for the whole cache TTL.
        final ApiKeyEntity fromDatabase = service.findOneByApiKey(apiKey);
        final ApiKeyEntity fromCache = service.findOneByApiKey(apiKey);

        assertNotNull(fromCache);
        assertEquals(fromDatabase.getScopes(), fromCache.getScopes(),
                "the cached key must carry the same scopes as the stored one");
        assertTrue(fromCache.hasScope(ApiKeyScope.REDACT));
        assertFalse(fromCache.hasScope(ApiKeyScope.LEDGER_EXPORT),
                "the cache must not widen a key's scopes");
    }

    @Test
    void updateScopesRefusesAKeyOwnedByAnotherUser() {
        final ObjectId owner = new ObjectId();
        final ObjectId attacker = new ObjectId();
        final ServiceResponse created = service.createApiKey("req", owner, "src",
                Set.of(ApiKeyScope.REDACT.getScope()));
        final ApiKeyEntity key = service.findOneByApiKey(created.getMessage());

        final ServiceResponse response = service.updateScopes("req", attacker, key,
                ApiKeyScope.all(), "api");

        assertFalse(response.isSuccessful(), "another user must not be able to widen this key");

        // Nothing was changed, and no audit event claims otherwise.
        final ApiKeyEntity reloaded = service.findOneByApiKey(created.getMessage());
        assertEquals(Set.of(ApiKeyScope.REDACT.getScope()), reloaded.getScopes());
        verify(auditEventPublisher, never()).auditEvent(any(), eq(AuditLogEvent.API_KEY_SCOPES_CHANGED),
                any(), any(), any(), any());
    }

    @Test
    void updateScopesIgnoresATamperedOwnerOnTheSuppliedEntity() {
        final ObjectId owner = new ObjectId();
        final ServiceResponse created = service.createApiKey("req", owner, "src",
                Set.of(ApiKeyScope.REDACT.getScope()));
        final ApiKeyEntity key = service.findOneByApiKey(created.getMessage());

        // The caller claims the key is theirs by rewriting the owner on the object they pass in. The
        // check reads stored state, so the claim is worthless.
        final ObjectId attacker = new ObjectId();
        key.setUserId(attacker);

        final ServiceResponse response = service.updateScopes("req", attacker, key,
                ApiKeyScope.all(), "api");

        assertFalse(response.isSuccessful(), "a tampered owner on the passed entity must not authorize the change");
        assertEquals(Set.of(ApiKeyScope.REDACT.getScope()),
                service.findOneByApiKey(created.getMessage()).getScopes());
    }

}
