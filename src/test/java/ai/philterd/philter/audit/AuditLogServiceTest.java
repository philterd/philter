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
package ai.philterd.philter.audit;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditLogServiceTest {

    private static final String HEADER =
            "timestamp,event,request_id,api_key_id,associated_object,client_ip_address,details";

    @Mock private MongoClient mongoClient;
    @Mock private MongoDatabase mongoDatabase;
    @Mock private MongoCollection<Document> collection;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("audit_events")).thenReturn(collection);
        auditLogService = new AuditLogService(mongoClient);
    }

    /** Stubs the find().sort().limit() chain to iterate the given documents; returns the iterable mock. */
    private FindIterable<Document> stubFind(final List<Document> documents) {
        final FindIterable<Document> findIterable = mock(FindIterable.class);
        when(collection.find(any(Bson.class))).thenReturn(findIterable);
        when(findIterable.sort(any())).thenReturn(findIterable);
        when(findIterable.limit(anyInt())).thenReturn(findIterable);
        final Iterator<Document> it = documents.iterator();
        final MongoCursor<Document> cursor = mock(MongoCursor.class);
        when(cursor.hasNext()).thenAnswer(inv -> it.hasNext());
        when(cursor.next()).thenAnswer(inv -> it.next());
        when(findIterable.iterator()).thenReturn(cursor);
        return findIterable;
    }

    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 10);

    private String export(final LocalDate from, final LocalDate to) {
        return new String(auditLogService.exportCsv(from, to), StandardCharsets.UTF_8);
    }

    @Test
    void emptyResultReturnsHeaderOnly() {
        stubFind(List.of());

        final String csv = export(FROM, TO);

        assertEquals(HEADER + "\n", csv);
    }

    @Test
    void writesHeaderAndOneRowPerEvent() {
        final Document a = new Document("event", "user_created").append("request_id", "r1")
                .append("api_key_id", "k1").append("associated_object", "o1")
                .append("client_ip_address", "1.2.3.4").append("details", "role: admin")
                .append("timestamp", Date.from(Instant.parse("2026-06-08T12:00:00Z")));
        final Document b = new Document("event", "user_deleted").append("request_id", "r2")
                .append("timestamp", Date.from(Instant.parse("2026-06-07T08:30:00Z")));

        stubFind(List.of(a, b));

        final String[] lines = export(FROM, TO).split("\n");

        assertEquals(HEADER, lines[0]);
        assertEquals("2026-06-08T12:00:00Z,user_created,r1,k1,o1,1.2.3.4,role: admin", lines[1]);
        // Missing fields render as empty cells (b has no api_key_id/associated_object/ip/details).
        assertEquals("2026-06-07T08:30:00Z,user_deleted,r2,,,,", lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    void timestampsAreFormattedAsIso8601() {
        stubFind(List.of(new Document("event", "e")
                .append("timestamp", Date.from(Instant.parse("2026-01-02T03:04:05Z")))));

        final String csv = export(FROM, TO);

        assertTrue(csv.contains("2026-01-02T03:04:05Z"), "timestamp should be ISO-8601: " + csv);
    }

    @Test
    void fieldsWithCommasQuotesAndNewlinesAreCsvEscaped() {
        // A details value containing a comma, a double-quote, and a newline must be quoted, with the
        // embedded quote doubled.
        stubFind(List.of(new Document("event", "e").append("details", "has,comma \"q\"\nline2")
                .append("timestamp", Date.from(Instant.parse("2026-01-01T00:00:00Z")))));

        final String csv = export(FROM, TO);

        assertTrue(csv.contains("\"has,comma \"\"q\"\"\nline2\""), "special chars should be escaped: " + csv);
    }

    @Test
    void plainFieldsAreNotQuoted() {
        stubFind(List.of(new Document("event", "user_created").append("details", "no special chars")
                .append("timestamp", Date.from(Instant.parse("2026-01-01T00:00:00Z")))));

        final String csv = export(FROM, TO);

        assertTrue(csv.contains(",no special chars"), "plain field should be unquoted: " + csv);
        assertFalse(csv.contains("\"no special chars\""), "plain field should not be quoted: " + csv);
    }

    @Test
    void queriesByTimestampRangeNewestFirstAndCapped() {
        final FindIterable<Document> fi = stubFind(List.of());

        auditLogService.exportCsv(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));

        // The query must restrict timestamp to [from, to).
        final ArgumentCaptor<Bson> queryCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(collection).find(queryCaptor.capture());
        final BsonDocument query = queryCaptor.getValue()
                .toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
        final String json = query.toJson();
        assertTrue(json.contains("timestamp"), json);
        assertTrue(json.contains("$gte"), json);
        assertTrue(json.contains("$lt"), json);

        // Newest first, capped at the export limit.
        verify(fi).sort(any());
        verify(fi).limit(AuditLogService.MAX_EXPORT_ROWS);
    }

    // ----- Window validation (enforced in the service) -----

    @Test
    void rejectsRangeWiderThanWindow() {
        final LocalDate from = LocalDate.of(2026, 6, 1);
        final LocalDate to = from.plusDays(AuditLogService.MAX_EXPORT_WINDOW_DAYS + 1);
        assertThrows(IllegalArgumentException.class, () -> auditLogService.exportCsv(from, to));
    }

    @Test
    void allowsRangeExactlyAtWindowLimit() {
        stubFind(List.of());
        final LocalDate from = LocalDate.of(2026, 6, 1);
        final LocalDate to = from.plusDays(AuditLogService.MAX_EXPORT_WINDOW_DAYS);
        // Exactly the limit is allowed: header-only export, no exception.
        assertEquals(HEADER + "\n", export(from, to));
    }

    @Test
    void rejectsReversedRange() {
        assertThrows(IllegalArgumentException.class,
                () -> auditLogService.exportCsv(LocalDate.of(2026, 6, 10), LocalDate.of(2026, 6, 1)));
    }

    @Test
    void rejectsNullDates() {
        assertThrows(IllegalArgumentException.class, () -> auditLogService.exportCsv(null, TO));
        assertThrows(IllegalArgumentException.class, () -> auditLogService.exportCsv(FROM, null));
    }

}
