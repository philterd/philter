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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.model.ApiKeyScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link ApiKeyDataService}. */
public interface ApiKeyDataServiceContract extends StorageContract {

    private ApiKeyEntity sessionKey(final String owner) {
        return storage().apiKeys().createSessionKey("req", owner, Set.of(ApiKeyScope.REDACT.getScope()), "test", null);
    }

    @Test
    default void apiKeyARevokedKeyNeverAuthenticates() {
        final String owner = RecordIds.newId();
        final String apiKey = storage().apiKeys().createApiKey("req", owner, "test").getMessage();
        final ApiKeyEntity key = storage().apiKeys().findOneByApiKey(apiKey);
        assertNotNull(key);
        assertTrue(storage().apiKeys().deleteByApiKey("req", owner, key, "test").isSuccessful());
        assertNull(storage().apiKeys().findOneByApiKey(apiKey));
        assertNull(storage().anotherInstance().apiKeys().findOneByApiKey(apiKey));
        // The record is kept.
        assertEquals(List.of(key.getId()), storage().apiKeys().findAll(owner, 0, 10, true).stream()
                .map(ApiKeyEntity::getId).toList());
    }

    @Test
    default void apiKeyAnExpiredSessionKeyNeverAuthenticates() {
        final ApiKeyEntity key = sessionKey(RecordIds.newId());
        assertTrue(storage().apiKeys().touchSessionKey(storage().apiKeys().findOneByApiKey(key.getApiKey())));
        storage().expireSessionKey(key.getId());
        // Authenticating a session key finds it and then extends its idle window, which a key past its time refuses.
        final ApiKeyEntity found = storage().anotherInstance().apiKeys().findOneByApiKey(key.getApiKey());
        assertTrue(found == null || !storage().anotherInstance().apiKeys().touchSessionKey(found));
        assertEquals(1, storage().apiKeys().expireSessionKeys(key.getId()));
        assertNull(storage().apiKeys().findOneByApiKey(key.getApiKey()));
        assertTrue(storage().apiKeys().isEndedSessionKey(key.getApiKey()));
    }

    @Test
    default void apiKeyConcurrentSweepsEndEachDueSessionKeyOnce() throws Exception {
        final String owner = RecordIds.newId();
        for (int i = 0; i < 3; i++) {
            storage().expireSessionKey(sessionKey(owner).getId());
        }
        final ApiKeyEntity live = sessionKey(owner);
        final List<Long> ended = StorageContract.concurrently(6,
                () -> storage().anotherInstance().apiKeys().expireSessionKeys(null));
        assertEquals(3, ended.stream().mapToLong(Long::longValue).sum());
        assertNotNull(storage().apiKeys().findOneByApiKey(live.getApiKey()));
    }

    @Test
    default void apiKeyChangesApplyOnlyToTheOwnersActiveKey() {
        final String owner = RecordIds.newId();
        final String apiKey = storage().apiKeys().createApiKey("req", owner, "test",
                Set.of(ApiKeyScope.REDACT.getScope())).getMessage();
        final ApiKeyEntity key = storage().apiKeys().findOneByApiKey(apiKey);
        final Set<String> scopes = Set.copyOf(key.getScopes());
        assertFalse(storage().apiKeys().updateScopes("req", RecordIds.newId(), key, Set.of(), "test").isSuccessful());
        assertFalse(storage().apiKeys().deleteByApiKey("req", RecordIds.newId(), key, "test").isSuccessful());
        assertEquals(scopes, Set.copyOf(storage().apiKeys().findOneByApiKey(apiKey).getScopes()));
        assertTrue(storage().apiKeys().deleteByApiKey("req", owner, key, "test").isSuccessful());
        assertFalse(storage().apiKeys().updateScopes("req", owner, key, Set.of(), "test").isSuccessful());
        assertEquals(scopes, Set.copyOf(storage().apiKeys().findAll(owner, 0, 10, true).get(0).getScopes()));
    }

    @Test
    default void apiKeyListingsCountPageAndOrderByCreationTime() {
        final String owner = RecordIds.newId();
        final List<String> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add(storage().apiKeys().findOneByApiKey(storage().apiKeys().createApiKey("req", owner, "test")
                    .getMessage()).getId());
        }
        storage().apiKeys().createApiKey("req", RecordIds.newId(), "test");
        assertEquals(5, storage().apiKeys().count(owner));
        final List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().apiKeys().findAllBySession(owner, offset, 2, null, false).forEach(key -> paged.add(key.getId()));
        }
        assertEquals(5, new HashSet<>(paged).size());
        assertEquals(created, paged);
    }

}
