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
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LegalHoldDataServiceTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> mongoCollection;
    @Mock private AuditEventPublisher auditEventPublisher;

    private LegalHoldDataService service;

    private final ObjectId userId = new ObjectId();
    private final ObjectId setByUserId = new ObjectId();

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("legal_holds")).thenReturn(mongoCollection);
        service = new LegalHoldDataService(mongoClient, auditEventPublisher);
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Test
    void createReturns400WhenReferenceIsBlank() {
        final ServiceResponse r = service.create("req", "   ", LegalHoldEntity.SCOPE_USER, "val", null, userId, setByUserId);
        assertFalse(r.isSuccessful());
        assertEquals(400, r.getStatusCode());
    }

    @Test
    void createReturns400WhenScopeTypeIsInvalid() {
        final ServiceResponse r = service.create("req", "REF-1", "bad_type", "val", null, userId, setByUserId);
        assertFalse(r.isSuccessful());
        assertEquals(400, r.getStatusCode());
    }

    @Test
    void createReturns400WhenScopeValueIsBlank() {
        final ServiceResponse r = service.create("req", "REF-1", LegalHoldEntity.SCOPE_USER, "   ", null, userId, setByUserId);
        assertFalse(r.isSuccessful());
        assertEquals(400, r.getStatusCode());
    }

    @Test
    void createReturns409WhenReferenceAlreadyExists() {
        // findByReference checks collection.find(...).first() - return an existing doc to simulate duplicate
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(holdDoc("REF-1", LegalHoldEntity.SCOPE_USER, "val"));
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final ServiceResponse r = service.create("req", "REF-1", LegalHoldEntity.SCOPE_USER, userId.toHexString(), null, userId, setByUserId);
        assertFalse(r.isSuccessful());
        assertEquals(409, r.getStatusCode());
    }

    @Test
    void createSuccessInsertsDocumentAndAudits() {
        // First call (findByReference) returns null (no duplicate), second call may be for insertOne
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final com.mongodb.client.result.InsertOneResult insertResult = mock(com.mongodb.client.result.InsertOneResult.class);
        when(insertResult.getInsertedId()).thenReturn(new org.bson.BsonObjectId(new ObjectId()));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertResult);

        final ServiceResponse r = service.create("req", "REF-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc123", "reason", userId, setByUserId);

        assertTrue(r.isSuccessful());
        assertEquals(201, r.getStatusCode());
        verify(mongoCollection).insertOne(any(Document.class));
        verify(auditEventPublisher).auditEvent(eq("req"), eq(AuditLogEvent.LEGAL_HOLD_SET),
                eq(userId), isNull(), isNull(), contains("REF-1"));
    }

    @Test
    void createAcceptsDocumentChainScopeType() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);
        final com.mongodb.client.result.InsertOneResult insertResult = mock(com.mongodb.client.result.InsertOneResult.class);
        when(insertResult.getInsertedId()).thenReturn(new org.bson.BsonObjectId(new ObjectId()));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertResult);

        final ServiceResponse r = service.create("req", "REF-2", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN,
                "docId", null, userId, setByUserId);
        assertTrue(r.isSuccessful());
    }

    @Test
    void createAcceptsUserScopeType() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);
        final com.mongodb.client.result.InsertOneResult insertResult = mock(com.mongodb.client.result.InsertOneResult.class);
        when(insertResult.getInsertedId()).thenReturn(new org.bson.BsonObjectId(new ObjectId()));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertResult);

        final ServiceResponse r = service.create("req", "REF-3", LegalHoldEntity.SCOPE_USER,
                userId.toHexString(), null, userId, setByUserId);
        assertTrue(r.isSuccessful());
    }

    // -------------------------------------------------------------------------
    // release
    // -------------------------------------------------------------------------

    @Test
    void releaseReturns404WhenHoldNotFound() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final ServiceResponse r = service.release("req", "MISSING", userId);
        assertFalse(r.isSuccessful());
        assertEquals(404, r.getStatusCode());
    }

    @Test
    void releaseDeletesHoldAndAudits() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(holdDoc("REF-1", LegalHoldEntity.SCOPE_USER, "val"));
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final DeleteResult deleteResult = mock(DeleteResult.class);
        when(deleteResult.getDeletedCount()).thenReturn(1L);
        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(deleteResult);

        final ServiceResponse r = service.release("req", "REF-1", userId);

        assertTrue(r.isSuccessful());
        assertEquals(200, r.getStatusCode());
        verify(mongoCollection).deleteOne(any(Bson.class));
        verify(auditEventPublisher).auditEvent(eq("req"), eq(AuditLogEvent.LEGAL_HOLD_RELEASED),
                eq(userId), isNull(), isNull(), contains("REF-1"));
    }

    // -------------------------------------------------------------------------
    // isProtectedDocument
    // -------------------------------------------------------------------------

    @Test
    void isProtectedDocumentReturnsFalseWhenNoHoldExists() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        assertFalse(service.isProtectedDocument(userId, "doc123"));
    }

    @Test
    void isProtectedDocumentReturnsTrueWhenDocumentChainHoldExists() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(holdDoc("REF-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc123"));
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        assertTrue(service.isProtectedDocument(userId, "doc123"));
    }

    @Test
    void isProtectedDocumentReturnsTrueWhenUserScopeHoldExists() {
        // A user-scope hold protects ALL of the user's documents.
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(holdDoc("REF-2", LegalHoldEntity.SCOPE_USER, userId.toHexString()));
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        assertTrue(service.isProtectedDocument(userId, "any-document-id"));
    }

    // -------------------------------------------------------------------------
    // hasAnyHold
    // -------------------------------------------------------------------------

    @Test
    void hasAnyHoldReturnsFalseWhenNoHolds() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(null);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        assertFalse(service.hasAnyHold(userId));
    }

    @Test
    void hasAnyHoldReturnsTrueWhenAtLeastOneHoldExists() {
        final FindIterable<Document> fi = mock(FindIterable.class);
        when(fi.first()).thenReturn(holdDoc("REF-1", LegalHoldEntity.SCOPE_USER, "val"));
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        assertTrue(service.hasAnyHold(userId));
    }

    // -------------------------------------------------------------------------
    // findAllByUserId / findAll
    // -------------------------------------------------------------------------

    @Test
    void findAllByUserIdReturnsEmptyListWhenNoneExist() {
        final FindIterable<Document> fi = emptyFindIterable();
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<LegalHoldEntity> result = service.findAllByUserId(userId, 0, 25);
        assertTrue(result.isEmpty());
    }

    @Test
    void findAllByUserIdReturnsHolds() {
        final Document doc = holdDoc("REF-1", LegalHoldEntity.SCOPE_USER, userId.toHexString());
        final FindIterable<Document> fi = iterableOf(doc);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<LegalHoldEntity> result = service.findAllByUserId(userId, 0, 25);
        assertEquals(1, result.size());
        assertEquals("REF-1", result.get(0).getReference());
    }

    @Test
    void findAllAdminReturnsAllHoldsAcrossUsers() {
        final Document docA = holdDoc("REF-A", LegalHoldEntity.SCOPE_USER, "u1");
        final Document docB = holdDoc("REF-B", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc1");
        final FindIterable<Document> fi = iterableOf(docA, docB);
        when(mongoCollection.find()).thenReturn(fi);

        final List<LegalHoldEntity> result = service.findAll(0, 25);
        assertEquals(2, result.size());
    }

    // -------------------------------------------------------------------------
    // findBlockingHoldsForDocument
    // -------------------------------------------------------------------------

    @Test
    void findBlockingHoldsForDocumentReturnsMatchingHolds() {
        final Document doc = holdDoc("REF-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc123");
        final FindIterable<Document> fi = iterableOf(doc);
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        final List<LegalHoldEntity> holds = service.findBlockingHoldsForDocument(userId, "doc123");
        assertEquals(1, holds.size());
        assertEquals("REF-1", holds.get(0).getReference());
    }

    @Test
    void findBlockingHoldsForDocumentReturnsEmptyWhenNoneBlock() {
        final FindIterable<Document> fi = emptyFindIterable();
        when(mongoCollection.find(any(Bson.class))).thenReturn(fi);

        assertTrue(service.findBlockingHoldsForDocument(userId, "doc123").isEmpty());
    }

    // -------------------------------------------------------------------------
    // fromDocument field mapping
    // -------------------------------------------------------------------------

    @Test
    void fromDocumentMapsAllFields() {
        final ObjectId id = new ObjectId();
        final Date now = new Date();
        final Document doc = new Document("_id", id)
                .append("user_id", userId)
                .append("reference", "REF-X")
                .append("scope_type", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN)
                .append("scope_value", "doc-abc")
                .append("reason", "Litigation")
                .append("set_at", now)
                .append("set_by_user_id", setByUserId);

        final LegalHoldEntity entity = LegalHoldEntity.fromDocument(doc);
        assertEquals(id, entity.getId());
        assertEquals(userId, entity.getUserId());
        assertEquals("REF-X", entity.getReference());
        assertEquals(LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, entity.getScopeType());
        assertEquals("doc-abc", entity.getScopeValue());
        assertEquals("Litigation", entity.getReason());
        assertEquals(now, entity.getSetAt());
        assertEquals(setByUserId, entity.getSetByUserId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Document holdDoc(final String reference, final String scopeType, final String scopeValue) {
        return new Document("_id", new ObjectId())
                .append("user_id", userId)
                .append("reference", reference)
                .append("scope_type", scopeType)
                .append("scope_value", scopeValue)
                .append("reason", null)
                .append("set_at", new Date())
                .append("set_by_user_id", setByUserId);
    }

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

    private FindIterable<Document> emptyFindIterable() {
        return iterableOf();
    }
}
