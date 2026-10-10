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

import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CSV export against a real (in-memory) MongoDB: the day boundaries, the zone, and the row cap. */
class AuditLogServiceExportIT extends AbstractMongoIT {

    private void event(final String name, final String timestamp) {
        mongoClient.getDatabase("philter").getCollection("audit_events").insertOne(new Document()
                .append("event", name)
                .append("details", "detail, with a comma")
                .append("timestamp", Date.from(Instant.parse(timestamp))));
    }

    private static String[] lines(final AuditLogService.CsvExport export) {
        return new String(export.csv(), StandardCharsets.UTF_8).split("\n");
    }

    @Test
    @DisplayName("Whole days in the requested zone, both ends inclusive, newest first, timestamps in UTC")
    void exportsWholeDaysInTheZone() {
        // 2026-10-01 in New York (UTC-4) runs from 04:00Z on the 1st to 04:00Z on the 2nd.
        event("before", "2026-10-01T03:59:59Z");
        event("first", "2026-10-01T04:00:00Z");
        event("last", "2026-10-02T03:59:59Z");
        event("after", "2026-10-02T04:00:00Z");

        final AuditLogService.CsvExport export = new AuditLogService(mongoClient)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("America/New_York"));

        final String[] lines = lines(export);
        assertEquals("timestamp,event,request_id,api_key_id,associated_object,client_ip_address,source,details", lines[0]);
        assertEquals(2, export.rows());
        assertFalse(export.truncated());
        assertEquals("America/New_York", export.zone().getId());
        assertEquals("2026-10-02T03:59:59Z,last,,,,,,\"detail, with a comma\"", lines[1]);
        assertTrue(lines[2].startsWith("2026-10-01T04:00:00Z,first,"), lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    @DisplayName("The limit keeps the newest events and says it cut the export short")
    void reportsTruncation() {
        event("oldest", "2026-10-01T01:00:00Z");
        event("middle", "2026-10-01T02:00:00Z");
        event("newest", "2026-10-01T03:00:00Z");

        final AuditLogService.CsvExport capped = new AuditLogService(mongoClient)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("UTC"), 0, 2);

        assertEquals(2, capped.rows());
        assertTrue(capped.truncated());
        final String csv = new String(capped.csv(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("newest") && csv.contains("middle") && !csv.contains("oldest"), csv);

        // Exactly at the limit is complete, not truncated.
        final AuditLogService.CsvExport exact = new AuditLogService(mongoClient)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("UTC"), 0, 3);
        assertEquals(3, exact.rows());
        assertFalse(exact.truncated());
    }

    @Test
    @DisplayName("Without a zone the server's is used")
    void defaultsToTheServerZone() {
        final AuditLogService.CsvExport export = new AuditLogService(mongoClient)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), null);

        assertEquals(ZoneId.systemDefault(), export.zone());
        assertEquals(0, export.rows());
    }

    @Test
    @DisplayName("A cell a spreadsheet would run as a formula is neutralized; ordinary cells are untouched")
    void neutralizesFormulas() {
        mongoClient.getDatabase("philter").getCollection("audit_events").insertOne(new Document()
                .append("event", "api_authentication_failed")
                // Neutralized whichever column it is in, wherever the value came from.
                .append("client_ip_address", "=HYPERLINK(\"https://attacker.example\",\"x\")")
                .append("details", "@SUM(1)")
                .append("request_id", "-1+1")
                .append("associated_object", "\tcmd")
                .append("timestamp", Date.from(Instant.parse("2026-10-01T12:00:00Z"))));
        event("ordinary", "2026-10-01T11:00:00Z");

        final String[] lines = lines(new AuditLogService(mongoClient)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("UTC")));

        assertEquals("2026-10-01T12:00:00Z,api_authentication_failed,'-1+1,,'\tcmd,"
                + "\"'=HYPERLINK(\"\"https://attacker.example\"\",\"\"x\"\")\",,'@SUM(1)", lines[1]);
        assertEquals("2026-10-01T11:00:00Z,ordinary,,,,,,\"detail, with a comma\"", lines[2]);
    }

    @Test
    @DisplayName("Paging a closed range returns each event exactly once, in one order, even with shared timestamps")
    void pagesEachEventExactlyOnce() {
        // Three events share one millisecond and three share another, so only the id orders them.
        for (final String name : List.of("a1", "a2", "a3")) {
            event(name, "2026-10-01T10:00:00Z");
        }
        for (final String name : List.of("b1", "b2", "b3")) {
            event(name, "2026-10-01T11:00:00Z");
        }
        event("c", "2026-10-01T09:00:00Z");
        event("d", "2026-10-01T12:00:00Z");

        final LocalDate day = LocalDate.parse("2026-10-01");
        final ZoneId utc = ZoneId.of("UTC");
        final List<String> whole = eventNames(new AuditLogService(mongoClient).export(day, day, utc));
        assertEquals(8, whole.size());

        final AuditLogService service = new AuditLogService(mongoClient);
        final List<String> paged = new ArrayList<>();
        int offset = 0;
        int pages = 0;
        AuditLogService.CsvExport page;
        do {
            page = service.export(day, day, utc, offset, 3);
            paged.addAll(eventNames(page));
            offset = page.nextOffset();
            pages++;
        } while (page.truncated());

        assertEquals(3, pages, "8 events at 3 a page");
        assertEquals(whole, paged, "the pages, in order, are the whole export");
        assertEquals(8, new HashSet<>(paged).size(), "no event repeats");
        assertEquals(2, page.rows());
        assertFalse(page.truncated());

        // The next offset counts the events returned, so pages need not share a limit.
        final List<String> mixed = new ArrayList<>();
        offset = 0;
        int call = 0;
        do {
            page = service.export(day, day, utc, offset, call++ % 2 == 0 ? 2 : 5);
            mixed.addAll(eventNames(page));
            offset = page.nextOffset();
        } while (page.truncated());
        assertEquals(whole, mixed, "pages of differing limits, in order, are the whole export");
    }

    @Test
    @DisplayName("An offset past the end is an empty, complete page; a negative offset is refused")
    void offsetBounds() {
        event("only", "2026-10-01T10:00:00Z");
        final LocalDate day = LocalDate.parse("2026-10-01");

        final AuditLogService.CsvExport past = new AuditLogService(mongoClient).export(day, day, ZoneId.of("UTC"), 5);
        assertEquals(0, past.rows());
        assertFalse(past.truncated());

        assertThrows(IllegalArgumentException.class,
                () -> new AuditLogService(mongoClient).export(day, day, ZoneId.of("UTC"), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditLogService(mongoClient).export(day, day, ZoneId.of("UTC"), 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditLogService(mongoClient).export(day, day, ZoneId.of("UTC"), 0, AuditLogService.MAX_EXPORT_ROWS + 1));
    }

    @Test
    @DisplayName("Paging by cursor returns each event exactly once while newer events are written between pages")
    void cursorPagingIsStableWhileEventsAreWritten() {
        // Two pairs share a millisecond, so a page can end between events only the id orders.
        for (int i = 0; i < 10; i++) {
            event("e" + i, "2026-10-01T10:00:0" + (i / 2) + "Z");
        }

        final LocalDate day = LocalDate.parse("2026-10-01");
        final ZoneId utc = ZoneId.of("UTC");
        final AuditLogService service = new AuditLogService(mongoClient);
        final List<String> before = eventNames(service.export(day, day, utc));
        assertEquals(10, before.size());

        // By offset, a newer event written between pages pushes the page boundary down a row.
        final AuditLogService.CsvExport first = service.export(day, day, utc, 0, 3);
        event("new-by-offset", "2026-10-01T23:00:00Z");
        final List<String> byOffset = new ArrayList<>(eventNames(first));
        byOffset.addAll(eventNames(service.export(day, day, utc, first.nextOffset(), 3)));
        assertEquals(5, new HashSet<>(byOffset).size(), "offset paging repeats the row the new event pushed down");

        // By cursor, newer events land before the cursor, so the pages after it hold the rest exactly once.
        final List<String> paged = new ArrayList<>();
        AuditLogService.CsvExport page = service.export(day, day, utc, 0, 3);
        final List<String> top = eventNames(page);
        paged.addAll(top);
        int written = 0;
        while (page.truncated()) {
            event("new-" + written++, "2026-10-01T23:00:00Z");
            page = service.export(day, day, utc, AuditLogService.ExportCursor.parse(page.nextCursor()), 3);
            paged.addAll(eventNames(page));
        }

        assertTrue(written >= 3, "events were written between pages");
        assertEquals(new HashSet<>(paged).size(), paged.size(), "no event repeats");
        // The first page was read after new-by-offset was written; every other event is in the order it had.
        assertEquals("new-by-offset", top.get(0));
        assertEquals(before, paged.subList(1, paged.size()), "nothing is missed, in order");
        assertFalse(paged.stream().anyMatch(name -> name.startsWith("new-") && !name.equals("new-by-offset")),
                "events written after the first page are not in the pages after its cursor");
    }

    @Test
    @DisplayName("A cursor survives its encoding, and a string that is not one is refused")
    void cursorEncoding() {
        final AuditLogService.ExportCursor cursor =
                new AuditLogService.ExportCursor(Date.from(Instant.parse("2026-10-01T10:00:00.123Z")), new org.bson.types.ObjectId());
        assertEquals(cursor, AuditLogService.ExportCursor.parse(cursor.encode()));

        for (final String bad : List.of("", "not-a-cursor", "MTIz", java.util.Base64.getUrlEncoder().encodeToString("x:y".getBytes(StandardCharsets.US_ASCII)))) {
            assertThrows(IllegalArgumentException.class, () -> AuditLogService.ExportCursor.parse(bad), bad);
        }
    }

    /** The event column of each data row. */
    private static List<String> eventNames(final AuditLogService.CsvExport export) {
        final List<String> names = new ArrayList<>();
        final String[] lines = lines(export);
        for (int i = 1; i < lines.length; i++) {
            names.add(lines[i].split(",", -1)[1]);
        }
        return names;
    }

}
