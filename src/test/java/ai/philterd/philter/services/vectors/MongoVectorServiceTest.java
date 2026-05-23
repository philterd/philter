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
package ai.philterd.philter.services.vectors;

import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.philter.audit.AuditEventPublisher;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertOneResult;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoVectorServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private final ObjectId userId = new ObjectId();

    private MongoVectorService service;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("vectors")).thenReturn(mongoCollection);
        service = new MongoVectorService(mongoClient, userId, auditEventPublisher);
    }

    @Test
    void insertsSetUserIdOnTheStoredDocument() {
        final InsertOneResult insertResult = mock(InsertOneResult.class);
        when(insertResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertResult);
        // Below cap: no eviction
        when(mongoCollection.countDocuments(any(Bson.class))).thenReturn(0L);

        final Span span = new Span();
        span.setFilterType(FilterType.PERSON);

        service.hashAndInsert("ctx", new double[]{1.2}, span, 8);

        final ArgumentCaptor<Document> docCaptor = ArgumentCaptor.forClass(Document.class);
        verify(mongoCollection).insertOne(docCaptor.capture());
        assertEquals(userId, docCaptor.getValue().get("user_id"),
                "Stored vector must carry user_id so getVectorRepresentation can find it");
    }

    @Test
    void zeroHashesAreSkipped() {
        final Span span = new Span();
        span.setFilterType(FilterType.PERSON);

        service.hashAndInsert("ctx", new double[]{0.0, 0.0}, span, 8);

        verify(mongoCollection, never()).insertOne(any(Document.class));
    }

    @Test
    void evictsOldestWhenAtCapacity() {
        final InsertOneResult insertResult = mock(InsertOneResult.class);
        when(insertResult.getInsertedId()).thenReturn(new BsonObjectId(new ObjectId()));
        when(mongoCollection.insertOne(any(Document.class))).thenReturn(insertResult);

        // First check (size before insert) -> at cap; subsequent checks irrelevant.
        when(mongoCollection.countDocuments(any(Bson.class)))
                .thenReturn((long) MongoVectorService.MAX_VECTORS_PER_CONTEXT);

        final ObjectId oldestId = new ObjectId();
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.first()).thenReturn(new Document("_id", oldestId));
        when(mongoCollection.deleteOne(any(Bson.class))).thenReturn(mock(DeleteResult.class));

        final Span span = new Span();
        span.setFilterType(FilterType.PERSON);

        service.hashAndInsert("ctx", new double[]{1.0}, span, 8);

        verify(mongoCollection, times(1)).deleteOne(any(Bson.class));
        verify(mongoCollection, times(1)).insertOne(any(Document.class));
    }

    @Test
    void getVectorRepresentationAggregatesCounts() {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.iterator()).thenReturn(cursor);
        when(cursor.hasNext()).thenReturn(true, true, true, false);
        when(cursor.next()).thenReturn(
                new Document("hash", 1.5).append("vector_size", 8).append("filter_type", "PERSON"),
                new Document("hash", 1.5).append("vector_size", 8).append("filter_type", "PERSON"),
                new Document("hash", 2.5).append("vector_size", 8).append("filter_type", "PERSON")
        );

        final var representation = service.getVectorRepresentation("ctx", FilterType.PERSON);

        assertEquals(2.0, representation.get(1.5), 1e-9);
        assertEquals(1.0, representation.get(2.5), 1e-9);
    }

}
