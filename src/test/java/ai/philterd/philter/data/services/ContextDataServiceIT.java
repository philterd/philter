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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Integration tests for {@link ContextDataService} against a real (in-memory) MongoDB. These cover
 * the create/find lifecycle, per-user name uniqueness (both the application-level check and the
 * unique compound index that backs it), delete authorization/cascade, and settings updates.
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
    void createAllowsSameNameForDifferentUsers() {
        // Names are unique per user, so two different users may each hold a context with the same name.
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        assertTrue(service.create("dup", userA).isSuccessful());

        final ServiceResponse response = service.create("dup", userB);
        assertTrue(response.isSuccessful());

        // Each user sees only their own context under that name.
        assertEquals(userA, service.findOne("dup", userA).getUserId());
        assertEquals(userB, service.findOne("dup", userB).getUserId());
    }

    @Test
    void createRejectsDuplicateNameForSameUser() {
        final ObjectId user = new ObjectId();
        assertTrue(service.create("dup", user).isSuccessful());

        final ServiceResponse response = service.create("dup", user);
        assertFalse(response.isSuccessful());
        assertEquals(409, response.getStatusCode());
    }

    @Test
    void uniqueIndexRejectsDirectDuplicateInsert() {
        // The unique compound index on (user_id, context_name) is created in the service constructor.
        // Insert directly, bypassing the application-level check, to prove the storage layer also
        // enforces per-user uniqueness: the same name under the SAME user must be rejected...
        final MongoCollection<Document> contexts = mongoClient.getDatabase("philter").getCollection("contexts");
        final ObjectId user = new ObjectId();
        contexts.insertOne(new Document("context_name", "x").append("user_id", user));

        assertThrows(MongoException.class, () ->
                contexts.insertOne(new Document("context_name", "x").append("user_id", user)));

        // ...while the same name under a DIFFERENT user is allowed.
        contexts.insertOne(new Document("context_name", "x").append("user_id", new ObjectId()));
    }

    @Test
    void createReturns409WhenTheInsertLosesTheUniqueNameRace() {
        // Deterministically exercise the insert path the race hits: make the non-atomic pre-check miss
        // (stub findOne to null) while the unique index already holds the (user, name) pair, so the
        // insert fails with a duplicate-key error. The service must convert that into a graceful 409
        // rather than letting the raw MongoWriteException escape (which would surface as a 500).
        final ContextDataService spied = spy(service);
        final String name = "already-present";
        final ObjectId user = new ObjectId();
        doReturn(null).when(spied).findOne(name, user);

        // Seed the same (user, name) directly so the unique index trips when the spied create() inserts.
        mongoClient.getDatabase("philter").getCollection("contexts")
                .insertOne(new Document("context_name", name).append("user_id", user));

        final ServiceResponse response = spied.create(name, user);

        assertFalse(response.isSuccessful());
        assertEquals(409, response.getStatusCode());
    }

    @Test
    void concurrentCreatesOfTheSameNameYieldOneSuccessAndGraceful409s() throws Exception {
        // Two+ concurrent creates of the same (user, name) can both pass the non-atomic findOne check
        // and then race on the insert. The unique index lets only one win; the losers must come back as
        // a tidy 409 rather than a raw MongoWriteException (which would surface as a 500). Starting every
        // thread from the same latch makes them collide on the insert path this fix handles. All threads
        // use the same user so the per-user uniqueness constraint is the one under contention.
        final int threads = 8;
        final String name = "race";
        final ObjectId user = new ObjectId();

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<ServiceResponse>> futures = new ArrayList<>();

        for(int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return service.create(name, user);
            }));
        }

        start.countDown();

        int successes = 0;
        int conflicts = 0;
        for(final Future<ServiceResponse> future : futures) {
            // future.get() rethrows anything create() threw — e.g. an unhandled duplicate-key write
            // exception — failing the test, which is exactly the regression being guarded against.
            final ServiceResponse response = future.get();
            if(response.isSuccessful()) {
                successes++;
            } else {
                assertEquals(409, response.getStatusCode(), "a racing loser must get a graceful 409");
                conflicts++;
            }
        }
        pool.shutdown();

        assertEquals(1, successes, "exactly one concurrent create should succeed");
        assertEquals(threads - 1, conflicts, "every other create should get a graceful 409");

        // The unique index guarantees a single stored context for the name regardless of the race.
        final MongoCollection<Document> contexts = mongoClient.getDatabase("philter").getCollection("contexts");
        assertEquals(1, contexts.countDocuments(new Document("context_name", name)));
    }

    @Test
    void deleteByNameRemovesContextAndCascadesToEntries() {
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("c", owner).isSuccessful());
        entryService.putReplacement(owner, "c", "John Smith", "David Jones", "PERSON");

        final ServiceResponse response = service.deleteByName("c", owner, false);
        assertTrue(response.isSuccessful());

        assertNull(service.findOne("c", owner));
        assertEquals(0, entryService.countByUserIdAndContext(owner, "c"));
    }

    @Test
    void deleteByNameDeniesNonOwner() {
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("c", owner).isSuccessful());

        // A different, non-admin user cannot delete a context they do not own; it remains.
        assertFalse(service.deleteByName("c", new ObjectId(), false).isSuccessful());
        assertNotNull(service.findOne("c", owner));
    }

    @Test
    void defaultContextIsDeletableLikeAnyOther() {
        // The "default" context is not reserved; its creator can delete it like any other.
        final ObjectId owner = new ObjectId();
        assertTrue(service.create("default", owner).isSuccessful());

        assertTrue(service.deleteByName("default", owner, false).isSuccessful());
        assertNull(service.findOne("default", owner));
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
