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

import ai.philterd.philter.data.RecordIds;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Read-only access to the audit log: the {@code GET /api/audit} listing and its CSV export. Events are written
 * through {@link AuditEventPublisher}.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>Events are listed most recent first by default, in a total order (timestamp, then record id), so offset
 *   pages of a range no longer receiving events hold each event exactly once, and {@link #count} matches the
 *   filters {@link #find} applies.</li>
 *   <li>Paging an export by cursor returns each event in the range exactly once, even while events are
 *   written, including the export's own audit events.</li>
 *   <li>The CSV has the same columns, order, and escaping in every implementation, with timestamps in UTC.</li>
 * </ul>
 */
public interface AuditLogService {

/**
     * The maximum number of (most recent) events included in an export, to bound memory use. If the log
     * is larger than this, the export contains the newest events up to this limit.
     */
    int MAX_EXPORT_ROWS = 100_000;

/** The maximum span, in days, allowed for a single export's from/to window. */
    int MAX_EXPORT_WINDOW_DAYS = 30;

    /** One audit event. The ids are record ids; {@code apiKeyId} names the acting user for most events. */
    record AuditEvent(Date timestamp, String event, String requestId, String apiKeyId, String associatedObject,
                      String clientIpAddress, String source, String details) {
    }

/**
     * An export: the CSV, how many events it holds, whether more remain after it, the time zone its dates
     * were read in, the offset it started at, and the cursor for the next page ({@code null} unless
     * {@link #truncated()}).
     */
    record CsvExport(byte[] csv, int rows, boolean truncated, ZoneId zone, int offset, String nextCursor) {

        /** As above, without a next cursor. */
        public CsvExport(final byte[] csv, final int rows, final boolean truncated, final ZoneId zone, final int offset) {
            this(csv, rows, truncated, zone, offset, null);
        }

        /** The offset of the next page. Meaningful only when {@link #truncated()}. */
        public int nextOffset() {
            return offset + rows;
        }

    }

/**
     * Where an export page ended: the last event's timestamp and id. The next page holds the events after it
     * in the export's order (older, or as old with a lower id), so events written after the export began,
     * which are newer, never shift it. Sent to clients as an opaque string.
     */
    record ExportCursor(Date timestamp, String id) {

        /** The cursor as clients see it. */
        public String encode() {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (timestamp.getTime() + ":" + id).getBytes(StandardCharsets.US_ASCII));
        }

        /**
         * Reads a cursor given by {@link #encode()}.
         *
         * @throws IllegalArgumentException if it is not one.
         */
        public static ExportCursor parse(final String cursor) {
            try {
                final String decoded = new String(Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.US_ASCII);
                final int colon = decoded.indexOf(':');
                final String id = decoded.substring(colon + 1);
                if (!RecordIds.isValid(id)) {
                    throw new IllegalArgumentException("not an id");
                }
                return new ExportCursor(new Date(Long.parseLong(decoded.substring(0, colon))), id.toLowerCase(Locale.ROOT));
            } catch (final RuntimeException ex) {
                throw new IllegalArgumentException("cursor is not a cursor from an export page.");
            }
        }

    }

/** As {@link #export}, in the server's time zone, returning only the CSV. */
    default byte[] exportCsv(final LocalDate fromInclusive, final LocalDate toInclusive) {
        return export(fromInclusive, toInclusive, null).csv();
    }

/**
     * Renders the audit log for the inclusive date range {@code [fromInclusive, toInclusive]} as CSV,
     * most recent first, capped at {@link #MAX_EXPORT_ROWS}.
     *
     * <p>The dates are whole calendar days in {@code zone}, or the server's time zone (the JVM default)
     * when it is null; the {@code toInclusive} day is included in full. The range must have both dates,
     * be ordered, and span no more than {@link #MAX_EXPORT_WINDOW_DAYS} days. Timestamps in the CSV are
     * UTC whatever the zone.
     *
     * @throws IllegalArgumentException if a date is missing, the range is reversed, or it exceeds the
     *                                  maximum window.
     */
    default CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone) {
        return export(fromInclusive, toInclusive, zone, 0);
    }

/**
     * As above, starting {@code offset} events into the range, so an export cut short by the cap can be
     * continued. The order is total (timestamp, then id), so pages of a range no longer receiving events
     * hold each event exactly once.
     *
     * @throws IllegalArgumentException as above, or if {@code offset} is negative.
     */
    default CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone,
                            final int offset) {
        return export(fromInclusive, toInclusive, zone, offset, MAX_EXPORT_ROWS);
    }

/**
     * As above, returning at most {@code limit} events, which may not exceed {@link #MAX_EXPORT_ROWS}.
     *
     * @throws IllegalArgumentException as above, or if {@code limit} is not between 1 and
     *                                  {@link #MAX_EXPORT_ROWS}.
     */
    CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone,
                            final int offset, final int limit);

/**
     * As above, returning the events after {@code cursor}, a page's {@link CsvExport#nextCursor()}. Unlike an
     * offset, a cursor is not shifted by events written while the export is paged, including the export's
     * own audit events, so pages of a range that includes the current day still hold each event exactly once.
     *
     * @throws IllegalArgumentException as above.
     */
    CsvExport export(final LocalDate fromInclusive, final LocalDate toInclusive, final ZoneId zone,
                            final ExportCursor cursor, final int limit);

/**
     * A page of audit events, most recent first. A null filter does not narrow the result;
     * {@code principalId} matches {@code api_key_id} and {@code event} the wire name.
     */
    default List<AuditEvent> find(final String principalId, final String event, final Date from,
                               final Date toExclusive, final int offset, final int limit) {
        return find(principalId, event, from, toExclusive, offset, limit, true);
    }

/** As above, oldest first unless {@code descending}. */
    List<AuditEvent> find(final String principalId, final String event, final Date from,
                               final Date toExclusive, final int offset, final int limit, final boolean descending);

/** How many events match the filters, so a caller can page through them. */
    long count(final String principalId, final String event, final Date from, final Date toExclusive);

}
