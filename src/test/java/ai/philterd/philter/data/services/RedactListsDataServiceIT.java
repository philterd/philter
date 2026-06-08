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
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link RedactListsDataService} against a real (in-memory) MongoDB. These
 * exercise the saveOrUpdate insert/update branches with real round-trips, prove that a second
 * saveOrUpdate updates rather than duplicates the per-user document, and verify that the lookup is
 * scoped to the owning user.
 */
class RedactListsDataServiceIT extends AbstractMongoIT {

    private RedactListsDataService service;

    @BeforeEach
    void setUpService() {
        service = new RedactListsDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    @Test
    void saveOrUpdatePersistsAndFindReadsItBack() {
        final ObjectId user = new ObjectId();
        service.saveOrUpdate("req", user, List.of("ssn", "secret"), List.of("public"), "source");

        final RedactListsEntity found = service.find(user);
        assertNotNull(found);
        assertEquals(user, found.getUserId());
        assertEquals(List.of("ssn", "secret"), found.getTermsToAlwaysRedact());
        assertEquals(List.of("public"), found.getTermsToNeverRedact());
    }

    @Test
    void findReturnsNullWhenNothingSaved() {
        assertNull(service.find(new ObjectId()));
    }

    @Test
    void secondSaveOrUpdateUpdatesAndDoesNotDuplicate() {
        final ObjectId user = new ObjectId();
        service.saveOrUpdate("req", user, List.of("a"), List.of("b"), "source");
        // A second save for the same user must update the existing document, not insert another.
        service.saveOrUpdate("req", user, List.of("c", "d"), List.of("e"), "source");

        final MongoCollection<Document> redactLists =
                mongoClient.getDatabase("philter").getCollection("redact_lists");
        assertEquals(1L, redactLists.countDocuments(new Document("user_id", user)));

        final RedactListsEntity found = service.find(user);
        assertEquals(List.of("c", "d"), found.getTermsToAlwaysRedact());
        assertEquals(List.of("e"), found.getTermsToNeverRedact());
    }

    @Test
    void redactListsAreScopedByUser() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.saveOrUpdate("req", userA, List.of("a"), List.of("b"), "source");

        // Another user has no redact lists of their own.
        assertNull(service.find(userB));

        // Saving for userB does not affect userA's lists.
        service.saveOrUpdate("req", userB, List.of("x"), List.of("y"), "source");
        assertEquals(List.of("a"), service.find(userA).getTermsToAlwaysRedact());
        assertEquals(List.of("x"), service.find(userB).getTermsToAlwaysRedact());
    }

    /**
     * Regression guard for the redact-lists scoping bug: the view used to read with a null user id
     * ({@code find(null)}). A null-scoped read must never surface a real user's saved lists — a
     * document owned by a real user id must not match a {@code {user_id: null}} query. The view now
     * reads with the signed-in user's id, but this proves that even the old call could not return
     * another user's lists (and that a null read yields nothing rather than someone else's data).
     */
    @Test
    void findWithNullUserIdDoesNotReturnAnotherUsersLists() {
        final ObjectId userA = new ObjectId();
        service.saveOrUpdate("req", userA, List.of("ssn", "secret"), List.of("public"), "source");

        // The old read pattern: a null id must not reveal userA's lists.
        assertNull(service.find(null));
    }

    /**
     * A user-scoped read must ignore an orphan (owner-less) redact-lists document — for example a
     * legacy row written before terms were per-user, which has no {@code user_id} field. Such a row
     * must not bleed into any real user's scoped lookup.
     */
    @Test
    void scopedFindIgnoresOrphanOwnerlessDocument() {
        final MongoCollection<Document> redactLists =
                mongoClient.getDatabase("philter").getCollection("redact_lists");
        // Insert a document with no user_id at all (simulates pre-per-user legacy data).
        redactLists.insertOne(new Document("terms_to_always_redact", List.of("legacy"))
                .append("terms_to_never_redact", List.of()));

        final ObjectId user = new ObjectId();
        service.saveOrUpdate("req", user, List.of("mine"), List.of(), "source");

        // The user sees only their own terms, never the orphan document.
        assertEquals(List.of("mine"), service.find(user).getTermsToAlwaysRedact());
        // A different user with nothing saved sees nothing — not the orphan.
        assertNull(service.find(new ObjectId()));
    }

}
