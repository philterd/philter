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
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.mongodb.MongoException;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link ContextDataService} against a real (in-memory) MongoDB. These cover
 * the create/find lifecycle, global name uniqueness (both the application-level check and the unique
 * index that backs it), owner-agnostic lookup, delete authorization/cascade, and settings updates.
 */
class ContextDataServiceIT extends AbstractMongoIT {

    private ContextDataService service;
    private ContextEntryDataService entryService;

    @BeforeEach
    void setUpServices() {
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);
        service = new ContextDataService(mongoClient, new ContextCache(null, 0, null, false), audit);
        entryService = new ContextEntryDataService(mongoClient, audit);
    }

    @Test
    void createThenFindOneScopedToOwner() {
        final ObjectId user = new ObjectId();
        assertTrue(service.create("c1", user).isSuccessful());

        final ContextEntity found = service.findOne("c1", user);
        assertNotNull(found);
        assertEquals(user, found.getUserId());

        // findOne is owner-scoped: another user cannot see it.
        assertNull(service.findOne("c1", new ObjectId()));
    }

    @Test
    void createRejectsGloballyDuplicateNameAcrossUsers() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        assertTrue(service.create("dup", userA).isSuccessful());

        final ServiceResponse response = service.create("dup", userB);
        assertFalse(response.isSuccessful());
        assertEquals(409, response.getStatusCode());
    }

    @Test
    void uniqueIndexRejectsDirectDuplicateInsert() {
        // The unique index on context_name is created in the service constructor. Insert directly,
        // bypassing the application-level check, to prove the storage layer also enforces uniqueness.
        final MongoCollection<Document> contexts = mongoClient.getDatabase("philter").getCollection("contexts");
        contexts.insertOne(new Document("context_name", "x").append("user_id", new ObjectId()));

        assertThrows(MongoException.class, () ->
                contexts.insertOne(new Document("context_name", "x").append("user_id", new ObjectId())));
    }

    @Test
    void findOneByNameIsOwnerAgnostic() {
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("c", owner).isSuccessful());

        final ContextEntity byName = service.findOneByName("c");
        assertNotNull(byName);
        assertEquals(owner, byName.getUserId());
    }

    @Test
    void deleteByNameRemovesContextAndCascadesToEntries() {
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("c", owner).isSuccessful());
        entryService.putReplacement(owner, "c", "John Smith", "David Jones", "PERSON");

        final ServiceResponse response = service.deleteByName("c", owner, false);
        assertTrue(response.isSuccessful());

        assertNull(service.findOneByName("c"));
        assertEquals(0, entryService.countByUserIdAndContext(owner, "c"));
    }

    @Test
    void deleteByNameDeniesNonOwner() {
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("c", owner).isSuccessful());

        // A different, non-admin user cannot delete a context they do not own; it remains.
        assertFalse(service.deleteByName("c", new ObjectId(), false).isSuccessful());
        assertNotNull(service.findOneByName("c"));
    }

    @Test
    void defaultContextIsDeletableLikeAnyOther() {
        // The "default" context is no longer reserved; its creator can delete it like any other.
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("default", owner).isSuccessful());

        assertTrue(service.deleteByName("default", owner, false).isSuccessful());
        assertNull(service.findOneByName("default"));
    }

    @Test
    void updateSettingsPersistsFlags() {
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("c", owner, false, false).isSuccessful());

        assertTrue(service.updateSettings("c", owner, true, true).isSuccessful());

        final ContextEntity updated = service.findOne("c", owner);
        assertTrue(updated.isDisambiguation());
        assertTrue(updated.isLedger());
    }

}
