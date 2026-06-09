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
import ai.philterd.philter.data.entities.PolicyVersionEntity;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PolicyVersionDataServiceTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> mongoCollection;
    @Mock private AuditEventPublisher auditEventPublisher;

    private PolicyVersionDataService service;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("policy_versions")).thenReturn(mongoCollection);
        // ensureIndex calls createIndex; Mockito returns null by default — fine, AbstractService wraps in try/catch.
        service = new PolicyVersionDataService(mongoClient, auditEventPublisher);
    }

    // -------------------------------------------------------------------------
    // findAllByName
    // -------------------------------------------------------------------------

    @Test
    void findAllByNameReturnsEmptyListWhenNoVersionsExist() {
        final FindIterable<Document> fi = emptyFindIterable();
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<PolicyVersionEntity> result = service.findAllByName("my-policy", new ObjectId(), 0, 25);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByNameReturnsVersionsOrderedByRevisionDescending() {
        final ObjectId userId = new ObjectId();
        final Document v3 = versionDoc("my-policy", userId, 3, "hash3");
        final Document v2 = versionDoc("my-policy", userId, 2, "hash2");
        final Document v1 = versionDoc("my-policy", userId, 1, "hash1");

        // Simulate MongoDB returning documents already sorted descending (the service relies on the
        // .sort(Sorts.descending("revision")) call; we just return them in that order here).
        final FindIterable<Document> fi = iterableOf(v3, v2, v1);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<PolicyVersionEntity> result = service.findAllByName("my-policy", userId, 0, 25);

        assertEquals(3, result.size());
        assertEquals(3, result.get(0).getRevision());
        assertEquals(2, result.get(1).getRevision());
        assertEquals(1, result.get(2).getRevision());
    }

    @Test
    void findAllByNameAppliesOffsetAndLimit() {
        final FindIterable<Document> fi = emptyFindIterable();
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        service.findAllByName("my-policy", new ObjectId(), 10, 5);

        verify(fi).skip(10);
        verify(fi).limit(5);
    }

    @Test
    void findAllByNamePopulatesEntityFieldsFromDocument() {
        final ObjectId userId = new ObjectId();
        final Date now = new Date();
        final Document doc = new Document("_id", new ObjectId())
                .append("name", "my-policy")
                .append("user_id", userId)
                .append("revision", 7)
                .append("content_hash", "abc123")
                .append("policy", "{\"identifiers\":{}}")
                .append("captured_timestamp", now);

        final FindIterable<Document> fi = iterableOf(doc);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<PolicyVersionEntity> result = service.findAllByName("my-policy", userId, 0, 25);

        assertEquals(1, result.size());
        final PolicyVersionEntity entity = result.get(0);
        assertEquals("my-policy", entity.getName());
        assertEquals(7, entity.getRevision());
        assertEquals("abc123", entity.getContentHash());
        assertEquals("{\"identifiers\":{}}", entity.getPolicy());
        assertEquals(userId, entity.getUserId());
        assertEquals(now, entity.getCapturedTimestamp());
    }

    // -------------------------------------------------------------------------
    // findByNameAndRevision
    // -------------------------------------------------------------------------

    @Test
    void findByNameAndRevisionReturnsNullWhenRevisionDoesNotExist() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final PolicyVersionEntity result =
                service.findByNameAndRevision("my-policy", new ObjectId(), 99);

        assertNull(result);
    }

    @Test
    void findByNameAndRevisionReturnsEntityWhenFound() {
        final ObjectId userId = new ObjectId();
        final Document doc = versionDoc("my-policy", userId, 5, "hash5");

        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(doc);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final PolicyVersionEntity result =
                service.findByNameAndRevision("my-policy", userId, 5);

        assertNotNull(result);
        assertEquals(5, result.getRevision());
        assertEquals("hash5", result.getContentHash());
    }

    @Test
    void findByNameAndRevisionReturnsNullForWrongUser() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        // The query scopes by user_id; simulate MongoDB returning nothing for this user.
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final PolicyVersionEntity result =
                service.findByNameAndRevision("my-policy", new ObjectId(), 3);

        assertNull(result);
    }

    // -------------------------------------------------------------------------
    // findTwoMostRecent
    // -------------------------------------------------------------------------

    @Test
    void findTwoMostRecentReturnsEmptyListWhenNoneExist() {
        final FindIterable<Document> fi = emptyFindIterable();
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<PolicyVersionEntity> result =
                service.findTwoMostRecent("my-policy", new ObjectId());

        assertTrue(result.isEmpty());
    }

    @Test
    void findTwoMostRecentReturnsAtMostTwoVersions() {
        final ObjectId userId = new ObjectId();
        final Document v5 = versionDoc("my-policy", userId, 5, "h5");
        final Document v4 = versionDoc("my-policy", userId, 4, "h4");

        final FindIterable<Document> fi = iterableOf(v5, v4);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<PolicyVersionEntity> result =
                service.findTwoMostRecent("my-policy", userId);

        assertEquals(2, result.size());
        assertEquals(5, result.get(0).getRevision());
        assertEquals(4, result.get(1).getRevision());
        // Must pass limit=2 to the underlying find call.
        verify(fi).limit(2);
    }

    @Test
    void findTwoMostRecentReturnsSingleElementWhenOnlyOneVersionExists() {
        final ObjectId userId = new ObjectId();
        final FindIterable<Document> fi = iterableOf(versionDoc("my-policy", userId, 1, "h1"));
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<PolicyVersionEntity> result =
                service.findTwoMostRecent("my-policy", userId);

        assertEquals(1, result.size());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Document versionDoc(final String name, final ObjectId userId, final int revision,
                                 final String hash) {
        return new Document("_id", new ObjectId())
                .append("name", name)
                .append("user_id", userId)
                .append("revision", revision)
                .append("content_hash", hash)
                .append("policy", "{\"identifiers\":{}}")
                .append("captured_timestamp", new Date());
    }

    /** Builds a FindIterable mock that iterates over the given documents. */
    @SafeVarargs
    private FindIterable<Document> iterableOf(final Document... docs) {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.sort(any())).thenReturn(fi);
        when(fi.skip(anyInt())).thenReturn(fi);
        when(fi.limit(anyInt())).thenReturn(fi);

        final Iterator<Document> it = List.of(docs).iterator();
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(fi.iterator()).thenReturn(cursor);
        return fi;
    }

    /** Builds a FindIterable mock that returns no documents. */
    private FindIterable<Document> emptyFindIterable() {
        return iterableOf();
    }
}
