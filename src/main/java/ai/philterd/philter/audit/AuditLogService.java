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

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Read-only access to the audit log ({@code audit_events}): the dashboard's CSV export and the
 * {@code GET /api/audit} listing. Writing is handled by {@link MongoDBAuditEventPublisher}.
 */
public class AuditLogService {

    private static final String DATABASE = "philter";
    private static final String COLLECTION = "audit_events";

    /** The columns, in order, written to the CSV export. */
    private static final String[] COLUMNS =
            {"timestamp", "event", "request_id", "api_key_id", "associated_object", "client_ip_address", "details"};

    /**
     * The maximum number of (most recent) events included in an export, to bound memory use. If the log
     * is larger than this, the export contains the newest events up to this limit.
     */
    public static final int MAX_EXPORT_ROWS = 100_000;

    /** The maximum span, in days, allowed for a single export's from/to window. */
    public static final int MAX_EXPORT_WINDOW_DAYS = 30;

    private final MongoCollection<Document> collection;

    public AuditLogService(final MongoClient mongoClient) {
        this.collection = mongoClient.getDatabase(DATABASE).getCollection(COLLECTION);
    }

    /**
     * Renders the audit log for the inclusive date range {@code [fromInclusive, toInclusive]} as CSV
     * bytes, most recent first, capped at {@link #MAX_EXPORT_ROWS}.
     *
     * <p>The dates are whole calendar days in the <strong>server's time zone</strong> (the JVM
     * default); the {@code toInclusive} day is included in full. The range is validated here: it must
     * have both dates, be ordered, and span no more than {@link #MAX_EXPORT_WINDOW_DAYS} days.
     *
     * @throws IllegalArgumentException if a date is missing, the range is reversed, or it exceeds the
     *                                  maximum window.
     */
    public byte[] exportCsv(final LocalDate fromInclusive, final LocalDate toInclusive) {

        if (fromInclusive == null || toInclusive == null) {
            throw new IllegalArgumentException("Both a from and a to date are required.");
        }
        if (fromInclusive.isAfter(toInclusive)) {
            throw new IllegalArgumentException("The from date must be on or before the to date.");
        }
        if (ChronoUnit.DAYS.between(fromInclusive, toInclusive) > MAX_EXPORT_WINDOW_DAYS) {
            throw new IllegalArgumentException("The date range cannot exceed " + MAX_EXPORT_WINDOW_DAYS + " days.");
        }

        // Whole calendar days in the server's time zone; the 'to' day is included in full by using the
        // start of the following day as the (exclusive) upper bound.
        final ZoneId zone = ZoneId.systemDefault();
        final Date from = Date.from(fromInclusive.atStartOfDay(zone).toInstant());
        final Date toExclusive = Date.from(toInclusive.plusDays(1).atStartOfDay(zone).toInstant());

        final Bson query = Filters.and(
                Filters.gte("timestamp", from),
                Filters.lt("timestamp", toExclusive));

        final StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", COLUMNS)).append('\n');

        for (final Document document : collection.find(query).sort(Sorts.descending("timestamp")).limit(MAX_EXPORT_ROWS)) {
            for (int i = 0; i < COLUMNS.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvEscape(format(document.get(COLUMNS[i]))));
            }
            csv.append('\n');
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);

    }

    /**
     * A page of audit events, most recent first. A null filter does not narrow the result;
     * {@code principalId} matches {@code api_key_id} and {@code event} the wire name.
     */
    public List<Document> find(final ObjectId principalId, final String event, final Date from,
                               final Date toExclusive, final int offset, final int limit) {

        final List<Document> events = new ArrayList<>();

        collection.find(filter(principalId, event, from, toExclusive))
                .sort(Sorts.descending("timestamp"))
                .skip(offset)
                .limit(limit)
                .forEach(events::add);

        return events;

    }

    /** How many events match the filters, so a caller can page through them. */
    public long count(final ObjectId principalId, final String event, final Date from, final Date toExclusive) {
        return collection.countDocuments(filter(principalId, event, from, toExclusive));
    }

    private static Bson filter(final ObjectId principalId, final String event, final Date from, final Date toExclusive) {

        final List<Bson> conditions = new ArrayList<>();

        if (principalId != null) {
            conditions.add(Filters.eq("api_key_id", principalId));
        }
        if (event != null && !event.isBlank()) {
            conditions.add(Filters.eq("event", event));
        }
        if (from != null) {
            conditions.add(Filters.gte("timestamp", from));
        }
        if (toExclusive != null) {
            conditions.add(Filters.lt("timestamp", toExclusive));
        }

        return conditions.isEmpty() ? Filters.empty() : Filters.and(conditions);

    }

    /** Formats a raw BSON value for CSV output (dates as ISO-8601; everything else via toString). */
    private static String format(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Date date) {
            return Instant.ofEpochMilli(date.getTime()).toString();
        }
        return value.toString();
    }

    /** Quotes a CSV field when it contains a comma, quote, or newline, doubling embedded quotes. */
    private static String csvEscape(final String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

}
