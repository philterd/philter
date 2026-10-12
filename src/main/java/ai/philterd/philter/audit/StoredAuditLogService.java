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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * The rules for reading the audit log, the same in every storage implementation: what an export may ask for,
 * how its days are bounded in a time zone, and the CSV it produces, with its columns, escaping, and cursor. An
 * implementation finds the stored events.
 */
public abstract class StoredAuditLogService implements AuditLogService {

    /** The columns, in order, written to the CSV export. */
    private static final String[] COLUMNS =
            {"timestamp", "event", "request_id", "api_key_id", "associated_object", "client_ip_address", "source", "details"};

    /** A stored event and its record id, which orders events that share a timestamp. */
    public record StoredEvent(String id, AuditEvent event) {
    }

    /**
     * Events at or after {@code from} and before {@code toExclusive}, most recent first by timestamp and then by
     * record id, after {@code cursor} in that order when it is not null, skipping {@code offset} and returning at
     * most {@code limit}.
     */
    protected abstract List<StoredEvent> findForExport(final Date from, final Date toExclusive,
            final ExportCursor cursor, final int offset, final int limit);

    @Override
    public CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone,
                            final int offset, final int limit) {
        return export(fromInclusive, toInclusive, zone, offset, null, limit);
    }

    @Override
    public CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone,
                            final ExportCursor cursor, final int limit) {
        return export(fromInclusive, toInclusive, zone, 0, cursor, limit);
    }

    private CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone,
                             final int offset, final ExportCursor cursor, final int limit) {

        if (offset < 0) {
            throw new IllegalArgumentException("offset must be zero or greater.");
        }
        if (limit < 1 || limit > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_EXPORT_ROWS + ".");
        }

        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("Both a from and a to date are required.");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("The from date must be on or before the to date.");
        }
        if (ChronoUnit.DAYS.between(fromInclusive, toInclusive) > MAX_EXPORT_WINDOW_DAYS) {
            throw new IllegalArgumentException("The date range cannot exceed " + MAX_EXPORT_WINDOW_DAYS + " days.");
        }

        // The 'to' day is included in full by using the start of the following day as the upper bound.
        final ZoneId effectiveZone = zone == null ? ZoneId.systemDefault() : zone;
        final Date from = Date.from(fromInclusive.atStartOfDay(effectiveZone).toInstant());
        final Date toExclusive = Date.from(toInclusive.plusDays(1).atStartOfDay(effectiveZone).toInstant());

        final StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", COLUMNS)).append('\n');

        // One more than the cap is read, only to learn whether the cap cut the export short.
        final List<StoredEvent> found = findForExport(from, toExclusive, cursor, offset, limit + 1);
        final boolean truncated = found.size() > limit;
        final List<StoredEvent> page = truncated ? found.subList(0, limit) : found;

        for (final StoredEvent stored : page) {
            final AuditEvent event = stored.event();
            final Object[] values = {event.timestamp(), event.event(), event.requestId(), event.apiKeyId(),
                    event.associatedObject(), event.clientIpAddress(), event.source(), event.details()};
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvEscape(format(values[i])));
            }
            csv.append('\n');
        }

        final StoredEvent last = page.isEmpty() ? null : page.get(page.size() - 1);
        final String nextCursor = truncated ? new ExportCursor(last.event().timestamp(), last.id()).encode() : null;

        return new CsvExport(csv.toString().getBytes(StandardCharsets.UTF_8), page.size(), truncated, effectiveZone,
                offset, nextCursor);

    }

    /** Formats a value for CSV output: dates as ISO-8601 in UTC, everything else as itself. */
    private static String format(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        return value.toString();
    }

    /**
     * Writes a value as one CSV cell, quoted when it contains a comma, quote, or line break, with embedded
     * quotes doubled. A value a spreadsheet would run as a formula is prefixed with an apostrophe first: the
     * client IP column comes from a request header, so a caller controls it.
     */
    private static String csvEscape(final String raw) {
        final String value = startsLikeAFormula(raw) ? "'" + raw : raw;
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static boolean startsLikeAFormula(final String value) {
        if (value.isEmpty()) {
            return false;
        }
        final char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

}
