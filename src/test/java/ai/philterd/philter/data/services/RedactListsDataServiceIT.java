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
import ai.philterd.philter.data.services.RedactListsDataService.ListContents;
import ai.philterd.philter.data.services.RedactListsDataService.RedactList;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
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
 * Integration tests for {@link RedactListsDataService} against a real (in-memory) MongoDB. These
 * exercise the saveOrUpdate insert/update branches with real round-trips, prove that a second
 * saveOrUpdate updates rather than duplicates the per-user document, and verify that the lookup is
 * scoped to the owning user.
 */
class RedactListsDataServiceIT extends AbstractMongoIT {

    private RedactListsDataService service;

    @BeforeEach
    void setUpService() {
        service = new RedactListsDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
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


    @Test
    void replacingAListForAUserWithNoneCreatesItAtRevisionOne() {
        final ObjectId user = new ObjectId();

        final ListContents written = service.replaceList("req", user, RedactList.ALWAYS, List.of("ssn"), null, "source");

        assertEquals(new ListContents(List.of("ssn"), 1L), written);
        assertEquals(new ListContents(List.of(), 0L), service.findList(user, RedactList.NEVER));
    }

    @Test
    void aWriteAtTheCurrentRevisionSucceedsAndAStaleOneIsRefused() {
        final ObjectId user = new ObjectId();
        service.replaceList("req", user, RedactList.ALWAYS, List.of("a"), 0L, "source");

        assertEquals(new ListContents(List.of("b"), 2L),
                service.replaceList("req", user, RedactList.ALWAYS, List.of("b"), 1L, "source"));

        // A second client that also read revision 1 is refused, and the first client's terms stand.
        assertNull(service.replaceList("req", user, RedactList.ALWAYS, List.of("c"), 1L, "source"));
        assertEquals(new ListContents(List.of("b"), 2L), service.findList(user, RedactList.ALWAYS));
    }

    @Test
    void expectingRevisionZeroIsRefusedOnceTheListHasBeenWritten() {
        final ObjectId user = new ObjectId();
        assertNotNull(service.replaceList("req", user, RedactList.NEVER, List.of("acme"), 0L, "source"));

        // Two clients that both read the empty list: the second must not create a second document.
        assertNull(service.replaceList("req", user, RedactList.NEVER, List.of("other"), 0L, "source"));
        assertEquals(new ListContents(List.of("acme"), 1L), service.findList(user, RedactList.NEVER));
        assertEquals(1L, mongoClient.getDatabase("philter").getCollection("redact_lists")
                .countDocuments(new Document("user_id", user)));
    }

    @Test
    void eachListHasItsOwnRevision() {
        final ObjectId user = new ObjectId();
        service.replaceList("req", user, RedactList.ALWAYS, List.of("a"), 0L, "source");

        // The never-redact list was never written, so it is still at 0 although the document exists.
        assertEquals(new ListContents(List.of("public"), 1L),
                service.replaceList("req", user, RedactList.NEVER, List.of("public"), 0L, "source"));

        // Writing one list leaves the other's terms and revision as they were.
        assertEquals(new ListContents(List.of("a"), 1L), service.findList(user, RedactList.ALWAYS));
    }

    @Test
    void writingBothListsMovesBothRevisionsSoAPerListWriterNotices() {
        final ObjectId user = new ObjectId();
        service.replaceList("req", user, RedactList.ALWAYS, List.of("a"), 0L, "source");

        service.saveOrUpdate("req", user, List.of("a"), List.of("b"), "source");

        // The terms did not change, but the list was written, so a client holding revision 1 is refused.
        assertNull(service.replaceList("req", user, RedactList.ALWAYS, List.of("x"), 1L, "source"));
        assertEquals(2L, service.findList(user, RedactList.ALWAYS).revision());
        assertEquals(1L, service.findList(user, RedactList.NEVER).revision());
    }

    @Test
    void writingOneListThroughSaveOrUpdateLeavesTheOtherAlone() {
        final ObjectId user = new ObjectId();
        service.saveOrUpdate("req", user, List.of("a"), List.of("b"), "source");

        service.saveOrUpdate("req", user, List.of("a", "c"), null, "source");

        assertEquals(new ListContents(List.of("a", "c"), 2L), service.findList(user, RedactList.ALWAYS));
        assertEquals(new ListContents(List.of("b"), 1L), service.findList(user, RedactList.NEVER));
    }

    @Test
    void listsWrittenBeforeRevisionsAreAtRevisionZero() {
        final ObjectId user = new ObjectId();
        mongoClient.getDatabase("philter").getCollection("redact_lists")
                .insertOne(new Document("user_id", user).append("terms_to_always_redact", List.of("legacy"))
                        .append("terms_to_never_redact", List.of()));

        assertEquals(new ListContents(List.of("legacy"), 0L), service.findList(user, RedactList.ALWAYS));
        assertEquals(new ListContents(List.of("new"), 1L),
                service.replaceList("req", user, RedactList.ALWAYS, List.of("new"), 0L, "source"));
    }

    @Test
    void replacesTheNonUniqueIndexEarlierBuildsMade() {
        final MongoCollection<Document> redactLists = mongoClient.getDatabase("philter").getCollection("redact_lists");
        for (final Document index : redactLists.listIndexes()) {
            if (!"_id_".equals(index.getString("name"))) {
                redactLists.dropIndex(index.getString("name"));
            }
        }
        redactLists.createIndex(new Document("user_id", 1));

        new RedactListsDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));

        final List<String> names = new java.util.ArrayList<>();
        redactLists.listIndexes().forEach(index -> names.add(index.getString("name")));
        assertFalse(names.contains("user_id_1"), names.toString());
        assertTrue(names.contains("user_id_unique"), names.toString());
    }

}
