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
                Set.of(ApiKeyScope.POLICIES_READ.getScope()), "webui");

        final ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditEventPublisher).auditEvent(eq("req-scope-change"),
                eq(AuditLogEvent.API_KEY_SCOPES_CHANGED), eq(entity.getId()), eq(entity.getId()),
                eq("webui"), details.capture());

        // The entry must say what the key held before and after, so an auditor can tell whether the
        // key was widened or narrowed rather than only that something changed.
        assertTrue(details.getValue().contains("redact"), "must record the previous scopes: " + details.getValue());
        assertTrue(details.getValue().contains("policies:read"), "must record the new scopes: " + details.getValue());
    }

    @Test
    void theScopeChangeEventIsASecurityEventAndCannotBeSwitchedOff() {
        // Security events are always recorded; only REDACTION_ACTIVITY events honour
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
                ApiKeyScope.all(), "webui");

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
                ApiKeyScope.all(), "webui");

        assertFalse(response.isSuccessful(), "a tampered owner on the passed entity must not authorize the change");
        assertEquals(Set.of(ApiKeyScope.REDACT.getScope()),
                service.findOneByApiKey(created.getMessage()).getScopes());
    }

}
