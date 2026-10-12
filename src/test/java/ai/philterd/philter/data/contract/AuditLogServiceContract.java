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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.model.AuditLogEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link AuditLogService}, over events written through the storage's audit event publisher. */
public interface AuditLogServiceContract extends StorageContract {

    private void publish(final String principal, final String requestId) {
        storage().auditEvents().auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, principal, null, "test", null);
    }

    @Test
    default void auditLogListsMostRecentFirstAndCountsWhatItFinds() throws Exception {
        final String principal = RecordIds.newId();
        for (int i = 0; i < 5; i++) {
            publish(principal, "request-" + i);
            Thread.sleep(2);
        }
        publish(RecordIds.newId(), "someone-else");
        final List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().auditLog().find(principal, null, null, null, offset, 2).forEach(event -> paged.add(event.requestId()));
        }
        assertEquals(List.of("request-4", "request-3", "request-2", "request-1", "request-0"), paged);
        assertEquals(5, storage().auditLog().count(principal, null, null, null));
        assertEquals(5, storage().auditLog().count(principal, AuditLogEvent.API_KEY_CREATED.getAuditLogEvent(), null,
                new Date(System.currentTimeMillis() + 60_000)));
    }

    @Test
    default void auditLogACursorExportReturnsEachEventOnceWhileEventsAreWritten() {
        final String principal = RecordIds.newId();
        final List<String> written = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            written.add("before-" + i);
            publish(principal, "before-" + i);
        }
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        final List<String> exported = new ArrayList<>();
        AuditLogService.CsvExport page = storage().auditLog().export(today, today, ZoneOffset.UTC, 0, 3);
        exported.addAll(requestIds(page));
        int during = 0;
        while (page.truncated()) {
            publish(principal, "during-" + during++);
            page = storage().anotherInstance().auditLog().export(today, today, ZoneOffset.UTC,
                    AuditLogService.ExportCursor.parse(page.nextCursor()), 3);
            exported.addAll(requestIds(page));
        }
        assertTrue(during > 0, "events were written between pages");
        assertEquals(exported.size(), exported.stream().distinct().count(), "no event repeats");
        assertTrue(exported.containsAll(written), "every event written before the export is in it");
        assertFalse(exported.isEmpty());
    }

    /** The request id column of each data row; every row this contract writes has one. */
    private static List<String> requestIds(final AuditLogService.CsvExport export) {
        final List<String> lines = Arrays.asList(new String(export.csv(), StandardCharsets.UTF_8).split("\n"));
        final List<String> header = Arrays.asList(lines.get(0).split(","));
        final int column = header.indexOf("request_id");
        assertTrue(column >= 0, "the CSV has a request_id column: " + lines.get(0));
        return lines.subList(1, lines.size()).stream().filter(line -> !line.isBlank())
                .map(line -> line.split(",")[column]).toList();
    }

}
