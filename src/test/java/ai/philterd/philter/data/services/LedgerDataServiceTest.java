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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import org.apache.commons.codec.digest.DigestUtils;
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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerDataServiceTest {

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> mongoCollection;
    @Mock private EncryptionService encryptionService;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Mock private LegalHoldDataService legalHoldDataService;

    private LedgerDataService ledgerDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("ledger")).thenReturn(mongoCollection);
        // Default: no holds active. Individual tests override as needed.
        when(legalHoldDataService.hasAnyHold(any())).thenReturn(false);
        when(legalHoldDataService.isProtectedDocument(any(), any())).thenReturn(false);
        when(legalHoldDataService.findAllHoldsForUser(any())).thenReturn(Collections.emptyList());
        when(legalHoldDataService.findBlockingHoldsForDocument(any(), any())).thenReturn(Collections.emptyList());
        ledgerDataService = new LedgerDataService(mongoClient, encryptionService, auditEventPublisher,
                legalHoldDataService);
    }

    @Test
    void isDocumentIdUnique() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(1)).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        assertTrue(ledgerDataService.isDocumentIdUnique(userId, documentId));
    }

    @Test
    void getLatestTransaction() {
        ObjectId userId = new ObjectId();
        String documentId = "doc123";
        Document doc = new Document("_id", new ObjectId())
                .append("document_id", documentId)
                .append("user_id", userId)
                .append("start_position", 0L);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(1)).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        LedgerEntity result = ledgerDataService.getLatestTransaction(userId, documentId);

        assertNotNull(result);
        assertEquals(documentId, result.getDocumentId());
    }

    @Test
    void deleteAllByUserIdSucceedsWhenNoHoldsActive() {
        final ObjectId userId = new ObjectId();
        final DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);
        when(deleteResult.getDeletedCount()).thenReturn(10L);

        final ServiceResponse response = ledgerDataService.deleteAllByUserId("req", userId);

        assertTrue(response.isSuccessful());
        verify(mongoCollection).deleteMany(any(Bson.class));
    }

    @Test
    void deleteAllByUserIdReturns423WhenHoldIsActive() {
        final ObjectId userId = new ObjectId();
        final LegalHoldEntity hold = holdEntityUser("REF-1", userId);
        when(legalHoldDataService.hasAnyHold(userId)).thenReturn(true);
        when(legalHoldDataService.findAllHoldsForUser(userId)).thenReturn(List.of(hold));

        final ServiceResponse response = ledgerDataService.deleteAllByUserId("req", userId);

        assertFalse(response.isSuccessful());
        assertEquals(423, response.getStatusCode());
        assertTrue(response.getMessage().contains("REF-1"));
        verify(mongoCollection, never()).deleteMany(any());
        verify(auditEventPublisher).auditEvent(anyString(),
                eq(AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION), eq(userId),
                isNull(), isNull(), contains("REF-1"));
    }

    @Test
    void countChainsByUserId() {
        ObjectId userId = new ObjectId();
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(5L);

        int count = ledgerDataService.countChainsByUserId(userId);

        assertEquals(5, count);
    }

    @Test
    void findChainsByUserId() {
        ObjectId userId = new ObjectId();
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<LedgerEntity> results = ledgerDataService.findChainsByUserId("req", userId, 0, 10, "source");

        assertNotNull(results);
        assertTrue(results.isEmpty());
    }

    @Test
    void findAllChainHeadsAcrossUsersIsPaged() {
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<LedgerEntity> results = ledgerDataService.findAllChainHeadsAcrossUsers(50, 25);

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(findIterable).skip(50);
        verify(findIterable).limit(25);
    }

    @Test
    void countAllChainHeads() {
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(42L);

        int count = ledgerDataService.countAllChainHeads();

        assertEquals(42, count);
    }

    @Test
    void deleteChainsByUserIdAndOlderThanSucceedsWhenNoHoldsActive() {
        final ObjectId userId = new ObjectId();
        final DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);
        when(deleteResult.getDeletedCount()).thenReturn(7L);

        final ServiceResponse response = ledgerDataService.deleteChainsByUserIdAndOlderThan("req", userId, 30);

        assertTrue(response.isSuccessful());
        assertEquals(200, response.getStatusCode());
        assertTrue(response.getMessage().contains("7"));
        verify(auditEventPublisher).auditEvent(eq("req"), eq(AuditLogEvent.REDACTION_LEDGER_DELETED),
                eq(userId), isNull(), isNull(), contains("deletedCount: 7"));
    }

    @Test
    void deleteChainsByUserIdAndOlderThanReturns423WhenUserScopeHoldActive() {
        final ObjectId userId = new ObjectId();
        final LegalHoldEntity hold = holdEntityUser("LIT-001", userId);
        when(legalHoldDataService.hasAnyHold(userId)).thenReturn(true);
        when(legalHoldDataService.findAllHoldsForUser(userId)).thenReturn(List.of(hold));

        final ServiceResponse response = ledgerDataService.deleteChainsByUserIdAndOlderThan("req", userId, 30);

        assertFalse(response.isSuccessful());
        assertEquals(423, response.getStatusCode());
        assertTrue(response.getMessage().contains("LIT-001"));
        verify(mongoCollection, never()).deleteMany(any());
        verify(auditEventPublisher).auditEvent(anyString(),
                eq(AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION), eq(userId),
                isNull(), isNull(), contains("LIT-001"));
    }

    @Test
    void deleteChainsByUserIdAndOlderThanReturns423WhenDocumentChainHoldActive() {
        final ObjectId userId = new ObjectId();
        final LegalHoldEntity hold = holdEntityDocChain("DOC-HOLD", userId, "doc123");
        when(legalHoldDataService.hasAnyHold(userId)).thenReturn(true);
        when(legalHoldDataService.findAllHoldsForUser(userId)).thenReturn(List.of(hold));

        final ServiceResponse response = ledgerDataService.deleteChainsByUserIdAndOlderThan("req", userId, 90);

        assertFalse(response.isSuccessful());
        assertEquals(423, response.getStatusCode());
        verify(mongoCollection, never()).deleteMany(any());
    }

    @Test
    void deleteByDocumentIdSucceedsWhenNoHoldsActive() {
        final ObjectId userId = new ObjectId();
        final DeleteResult deleteResult = mock(DeleteResult.class);
        when(mongoCollection.deleteMany(any(Bson.class))).thenReturn(deleteResult);

        final ServiceResponse response = ledgerDataService.deleteByDocumentId("req", userId, "doc123", "API");

        assertTrue(response.isSuccessful());
        verify(mongoCollection).deleteMany(any(Bson.class));
        verify(auditEventPublisher).auditEvent(anyString(),
                eq(AuditLogEvent.REDACTION_LEDGER_DELETED), eq(userId),
                isNull(), eq("API"), contains("doc123"));
    }

    @Test
    void deleteByDocumentIdReturns423WhenDocumentChainHoldActive() {
        final ObjectId userId = new ObjectId();
        final LegalHoldEntity hold = holdEntityDocChain("LIT-DOC", userId, "doc123");
        when(legalHoldDataService.isProtectedDocument(userId, "doc123")).thenReturn(true);
        when(legalHoldDataService.findBlockingHoldsForDocument(userId, "doc123"))
                .thenReturn(List.of(hold));

        final ServiceResponse response = ledgerDataService.deleteByDocumentId("req", userId, "doc123", "API");

        assertFalse(response.isSuccessful());
        assertEquals(423, response.getStatusCode());
        assertTrue(response.getMessage().contains("LIT-DOC"));
        verify(mongoCollection, never()).deleteMany(any());
        verify(auditEventPublisher).auditEvent(anyString(),
                eq(AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION), eq(userId),
                isNull(), isNull(), contains("LIT-DOC"));
    }

    @Test
    void deleteByDocumentIdReturns423WhenUserScopeHoldActive() {
        final ObjectId userId = new ObjectId();
        final LegalHoldEntity hold = holdEntityUser("USER-HOLD", userId);
        when(legalHoldDataService.isProtectedDocument(userId, "doc999")).thenReturn(true);
        when(legalHoldDataService.findBlockingHoldsForDocument(userId, "doc999"))
                .thenReturn(List.of(hold));

        final ServiceResponse response = ledgerDataService.deleteByDocumentId("req", userId, "doc999", "UI");

        assertFalse(response.isSuccessful());
        assertEquals(423, response.getStatusCode());
        assertTrue(response.getMessage().contains("USER-HOLD"));
        verify(mongoCollection, never()).deleteMany(any());
    }

    @Test
    void deleteByDocumentIdCanBeBlockedByMultipleHolds() {
        final ObjectId userId = new ObjectId();
        final LegalHoldEntity h1 = holdEntityDocChain("LIT-A", userId, "docX");
        final LegalHoldEntity h2 = holdEntityUser("LIT-B", userId);
        when(legalHoldDataService.isProtectedDocument(userId, "docX")).thenReturn(true);
        when(legalHoldDataService.findBlockingHoldsForDocument(userId, "docX"))
                .thenReturn(List.of(h1, h2));

        final ServiceResponse response = ledgerDataService.deleteByDocumentId("req", userId, "docX", "API");

        assertFalse(response.isSuccessful());
        assertTrue(response.getMessage().contains("LIT-A"));
        assertTrue(response.getMessage().contains("LIT-B"));
    }

    @Test
    void searchChainsByUserIdAuditsHashNotSearchTerm() {
        ObjectId userId = new ObjectId();
        String searchTerm = "sensitive-patient-name";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        ledgerDataService.searchChainsByUserId("req", userId, searchTerm, "source");

        String expectedHash = DigestUtils.sha256Hex(searchTerm);
        verify(auditEventPublisher).auditEvent(eq("req"), eq(AuditLogEvent.REDACTION_LEDGER_QUERY),
                eq(userId), isNull(), eq("source"), eq("searchTermHash: " + expectedHash));
        // The raw search term must never appear in the audit details.
        verify(auditEventPublisher, never()).auditEvent(anyString(), any(), any(), any(), anyString(), contains(searchTerm));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private LegalHoldEntity holdEntityUser(final String reference, final ObjectId userId) {
        final LegalHoldEntity e = new LegalHoldEntity();
        e.setId(new ObjectId());
        e.setUserId(userId);
        e.setReference(reference);
        e.setScopeType(LegalHoldEntity.SCOPE_USER);
        e.setScopeValue(userId.toHexString());
        return e;
    }

    private LegalHoldEntity holdEntityDocChain(final String reference, final ObjectId userId,
                                                final String documentId) {
        final LegalHoldEntity e = new LegalHoldEntity();
        e.setId(new ObjectId());
        e.setUserId(userId);
        e.setReference(reference);
        e.setScopeType(LegalHoldEntity.SCOPE_DOCUMENT_CHAIN);
        e.setScopeValue(documentId);
        return e;
    }
}
