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
import ai.philterd.philter.data.entities.ContextEntryEntity;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.InsertOneResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextEntryDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private ContextEntryDataService contextEntryDataService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("context_entries")).thenReturn(mongoCollection);
        contextEntryDataService = new ContextEntryDataService(mongoClient, auditEventPublisher);
    }

    @Test
    void findAllByUserIdAndContext() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.sort(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.skip(anyInt())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(false);

        List<ContextEntryEntity> results = contextEntryDataService.findAllByUserIdAndContext(userId, contextName, 10);

        assertNotNull(results);
        assertTrue(results.isEmpty());
        verify(mongoCollection).find(any(Document.class));
    }

    @Test
    void containsToken() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String token = "sensitive";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document());

        boolean contains = contextEntryDataService.containsToken(userId, contextName, token);

        assertTrue(contains);
    }

    @Test
    void containsReplacement() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String replacement = "redacted";
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document());

        boolean contains = contextEntryDataService.containsReplacement(userId, contextName, replacement);

        assertTrue(contains);
    }

    @Test
    void getReplacement() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String token = "sensitive";
        String replacement = "redacted";
        Document doc = new Document("_id", new ObjectId())
                .append("user_id", userId)
                .append("context_name", contextName)
                .append("replacement", replacement)
                .append("reads", 0L);
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        String result = contextEntryDataService.getReplacement(userId, contextName, token);

        assertEquals(replacement, result);
        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void putReplacement() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        String token = "sensitive";
        String replacement = "redacted";
        String filterType = "type";

        // Mock containsToken to return false
        FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);

        contextEntryDataService.putReplacement(userId, contextName, token, replacement, filterType);

        verify(mongoCollection).insertOne(any(Document.class));
    }

    @Test
    void deleteByContextName() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";

        contextEntryDataService.deleteByContextName(contextName, userId);

        verify(mongoCollection).deleteMany(any(Document.class));
    }

    @Test
    void deleteByContextNameDefault() {
        ObjectId userId = new ObjectId();
        String contextName = "default";

        contextEntryDataService.deleteByContextName(contextName, userId);

        verify(mongoCollection, never()).deleteMany(any(Document.class));
    }

    @Test
    void getFilterTypeCounts() {
        ObjectId userId = new ObjectId();
        String contextName = "testContext";
        AggregateIterable<Document> aggregateIterable = mock(AggregateIterable.class);
        when(mongoCollection.aggregate(anyList())).thenReturn(aggregateIterable);
        Document resultDoc = new Document("_id", "type1").append("count", 5);
        MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(aggregateIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(resultDoc);

        Map<String, Long> counts = contextEntryDataService.getFilterTypeCounts(contextName, userId);

        assertNotNull(counts);
        assertEquals(1, counts.size());
        assertEquals(5L, counts.get("type1"));
    }

    @Test
    void saveThrowsException() {
        assertThrows(UnsupportedOperationException.class, () -> contextEntryDataService.save(new ContextEntryEntity()));
    }

    @Test
    void putReplacementEvictsLeastReadWhenAtCapacity() {
        final ObjectId userId = new ObjectId();
        final String contextName = "ctx";

        // First call is containsToken (returns null = no match); second call is the eviction lookup.
        final FindIterable<Document> containsFind = mock(FindIterable.class);
        when(containsFind.first()).thenReturn(null);

        final ObjectId victimId = new ObjectId();
        final FindIterable<Document> evictFind = mock(FindIterable.class);
        when(evictFind.sort(any(Bson.class))).thenReturn(evictFind);
        when(evictFind.first()).thenReturn(new Document("_id", victimId).append("reads", 0L));

        when(mongoCollection.find(any(Bson.class)))
                .thenReturn(containsFind)
                .thenReturn(evictFind);

        when(mongoCollection.countDocuments(any(Bson.class)))
                .thenReturn((long) ContextEntryDataService.MAX_CONTEXT_SIZE);

        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(mock(com.mongodb.client.result.DeleteResult.class));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(mock(InsertOneResult.class));

        contextEntryDataService.putReplacement(userId, contextName, "tok", "REPLACEMENT", "PERSON");

        verify(mongoCollection, times(1)).deleteOne(any(Bson.class));
        verify(mongoCollection, times(1)).insertOne(any(Document.class));
    }

    @Test
    void putReplacementSkipsEvictionWhenUnderCapacity() {
        final ObjectId userId = new ObjectId();

        final FindIterable<Document> containsFind = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(containsFind);
        when(containsFind.first()).thenReturn(null);

        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(0L);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(mock(InsertOneResult.class));

        contextEntryDataService.putReplacement(userId, "ctx", "tok", "R", "PERSON");

        verify(mongoCollection, never()).deleteOne(any(Bson.class));
        verify(mongoCollection, times(1)).insertOne(any(Document.class));
    }

    @Test
    void incrementReadsIssuesIncUpdate() {
        final ObjectId id = new ObjectId();
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(mock(com.mongodb.client.result.UpdateResult.class));

        contextEntryDataService.incrementReads(id);

        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void deleteByIdAndUserIdReturnsCount() {
        final com.mongodb.client.result.DeleteResult result = mock(com.mongodb.client.result.DeleteResult.class);
        when(result.getDeletedCount()).thenReturn(1L);
        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(result);

        assertEquals(1L, contextEntryDataService.deleteByIdAndUserId(new ObjectId(), new ObjectId()));
    }

    @Test
    void findOneEntryByTokenReturnsEntityWhenPresent() {
        final ObjectId id = new ObjectId();
        final Document doc = new Document("_id", id)
                .append("replacement", "R")
                .append("reads", 0L);
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(doc);

        final ContextEntryEntity entry = contextEntryDataService.findOneEntryByToken(new ObjectId(), "ctx", "tok");
        assertNotNull(entry);
        assertEquals(id, entry.getId());
        assertEquals("R", entry.getReplacement());
    }

    @Test
    void findAllByUserIdAndContextUnboundedReturnsAllEntries() {
        final ObjectId userId = new ObjectId();
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Document.class))).thenReturn(findIterable);
        when(findIterable.sort(any(Bson.class))).thenReturn(findIterable);
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, false);
        when(cursor.next()).thenReturn(new Document("_id", new ObjectId())
                .append("token_hash", "abc").append("replacement", "R").append("reads", 0L));

        final List<ContextEntryEntity> results = contextEntryDataService.findAllByUserIdAndContext(userId, "ctx");

        assertEquals(1, results.size());
        assertEquals("R", results.get(0).getReplacement());
        // No skip/limit is applied for the unbounded export variant.
        verify(findIterable, never()).limit(anyInt());
    }

    @Test
    void importEntryByHashInsertsWhenAbsent() {
        final ObjectId userId = new ObjectId();
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(0L);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(mock(InsertOneResult.class));

        final ContextEntryDataService.ImportOutcome outcome = contextEntryDataService.importEntryByHash(
                userId, "ctx", "a".repeat(64), "R", "PERSON", false, false);

        assertEquals(ContextEntryDataService.ImportOutcome.INSERTED, outcome);
        verify(mongoCollection).insertOne(any(Document.class));
        verify(mongoCollection, never()).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void importEntryByHashSkipsExistingWhenNotOverwriting() {
        final ObjectId userId = new ObjectId();
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document("_id", new ObjectId()));

        final ContextEntryDataService.ImportOutcome outcome = contextEntryDataService.importEntryByHash(
                userId, "ctx", "a".repeat(64), "R", "PERSON", false, false);

        assertEquals(ContextEntryDataService.ImportOutcome.SKIPPED, outcome);
        verify(mongoCollection, never()).insertOne(any(Document.class));
        verify(mongoCollection, never()).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void exportImportRoundTripPreservesMappingKeyedByTokenHash() {
        // The original token never leaves Philter; an export carries only its SHA-256 hash. Because
        // that hash is deterministic, re-importing reproduces a mapping that the same original token
        // will resolve to in any environment. This test exercises that round trip end to end through
        // the export DTOs.
        final ObjectId userId = new ObjectId();
        final String token = "John Smith";
        final String tokenHash = ai.philterd.philter.services.encryption.EncryptionService.hashSha256(token);

        // Build an export document the way the export endpoint does, then serialize/deserialize it.
        final ai.philterd.philter.api.responses.ContextEntriesExport export =
                new ai.philterd.philter.api.responses.ContextEntriesExport("src",
                        List.of(new ai.philterd.philter.api.responses.ContextEntryExport(tokenHash, "David Jones", "PERSON", false)));
        final String json = new com.google.gson.Gson().toJson(export);
        final ai.philterd.philter.api.responses.ContextEntriesExport reloaded =
                new com.google.gson.Gson().fromJson(json, ai.philterd.philter.api.responses.ContextEntriesExport.class);
        final ai.philterd.philter.api.responses.ContextEntryExport entry = reloaded.getEntries().get(0);

        // Import into a fresh (empty) target context.
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(null);
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(0L);
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(mock(InsertOneResult.class));

        final ContextEntryDataService.ImportOutcome outcome = contextEntryDataService.importEntryByHash(
                userId, "dst", entry.getTokenHash(), entry.getReplacement(), entry.getFilterType(), entry.isReplacementUuid(), false);

        assertEquals(ContextEntryDataService.ImportOutcome.INSERTED, outcome);

        // The stored mapping is keyed by exactly hash("John Smith"), so a redaction of "John Smith"
        // in the destination environment will hit it and yield the same replacement.
        final ArgumentCaptor<Document> inserted = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(inserted.capture());
        assertEquals(tokenHash, inserted.getValue().getString("token_hash"));
        assertEquals("David Jones", inserted.getValue().getString("replacement"));
        assertEquals("PERSON", inserted.getValue().getString("filter_type"));
    }

    @Test
    void importEntryByHashOverwritesExistingWhenOverwriting() {
        final ObjectId userId = new ObjectId();
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document("_id", new ObjectId()));
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(mock(com.mongodb.client.result.UpdateResult.class));

        final ContextEntryDataService.ImportOutcome outcome = contextEntryDataService.importEntryByHash(
                userId, "ctx", "a".repeat(64), "R2", "PERSON", false, true);

        assertEquals(ContextEntryDataService.ImportOutcome.OVERWRITTEN, outcome);
        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
        verify(mongoCollection, never()).insertOne(any(Document.class));
    }

}
