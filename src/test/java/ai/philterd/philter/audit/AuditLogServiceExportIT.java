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
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("timestamp,event,request_id,api_key_id,associated_object,client_ip_address,details", lines[0]);
        assertEquals(2, export.rows());
        assertFalse(export.truncated());
        assertEquals("America/New_York", export.zone().getId());
        assertEquals("2026-10-02T03:59:59Z,last,,,,,\"detail, with a comma\"", lines[1]);
        assertTrue(lines[2].startsWith("2026-10-01T04:00:00Z,first,"), lines[2]);
        assertEquals(3, lines.length);
    }

    @Test
    @DisplayName("The cap keeps the newest events and says it cut the export short")
    void reportsTruncation() {
        event("oldest", "2026-10-01T01:00:00Z");
        event("middle", "2026-10-01T02:00:00Z");
        event("newest", "2026-10-01T03:00:00Z");

        final AuditLogService service = new AuditLogService(mongoClient, 2);
        final AuditLogService.CsvExport capped = service
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("UTC"));

        assertEquals(2, capped.rows());
        assertTrue(capped.truncated());
        final String csv = new String(capped.csv(), StandardCharsets.UTF_8);
        assertTrue(csv.contains("newest") && csv.contains("middle") && !csv.contains("oldest"), csv);

        // Exactly at the cap is complete, not truncated.
        final AuditLogService.CsvExport exact = new AuditLogService(mongoClient, 3)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("UTC"));
        assertEquals(3, exact.rows());
        assertFalse(exact.truncated());
    }

    @Test
    @DisplayName("Without a zone the server's is used, as the dashboard does")
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
                // X-Forwarded-For is copied into this column, so a caller chooses it.
                .append("client_ip_address", "=HYPERLINK(\"https://attacker.example\",\"x\")")
                .append("details", "@SUM(1)")
                .append("request_id", "-1+1")
                .append("associated_object", "\tcmd")
                .append("timestamp", Date.from(Instant.parse("2026-10-01T12:00:00Z"))));
        event("ordinary", "2026-10-01T11:00:00Z");

        final String[] lines = lines(new AuditLogService(mongoClient)
                .export(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"), ZoneId.of("UTC")));

        assertEquals("2026-10-01T12:00:00Z,api_authentication_failed,'-1+1,,'\tcmd,"
                + "\"'=HYPERLINK(\"\"https://attacker.example\"\",\"\"x\"\")\",'@SUM(1)", lines[1]);
        assertEquals("2026-10-01T11:00:00Z,ordinary,,,,,\"detail, with a comma\"", lines[2]);
    }

}
