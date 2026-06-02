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
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link ApiKeyDataService} against a real (in-memory) MongoDB. These exercise
 * the create/find lifecycle, hash-based lookup, user scoping, counting, paging, and the soft-delete
 * behavior (keys are marked deleted, not removed) end to end — behavior the mock-based unit tests can
 * only approximate.
 */
class ApiKeyDataServiceIT extends AbstractMongoIT {

    private ApiKeyDataService service;

    @BeforeEach
    void setUpService() {
        service = new ApiKeyDataService(mongoClient, mock(AuditEventPublisher.class));
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
        assertTrue(service.deleteByApiKey("req", entity, "src").isSuccessful());
        assertTrue(entity.isDeleted());

        // A soft-deleted key is no longer found, counted, or listed.
        assertNull(service.findOneByApiKey(apiKey));
        assertEquals(0, service.count(user));
        assertTrue(service.findAll(user, 0, 10).isEmpty());
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

}
