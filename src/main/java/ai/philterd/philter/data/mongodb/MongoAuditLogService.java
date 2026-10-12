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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.mongodb.MongoIds;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** MongoDB implementation of {@link AuditLogService}, reading the {@code audit_events} collection. */
public class MongoAuditLogService implements AuditLogService {

    private static final String DATABASE = "philter";
    private static final String COLLECTION = "audit_events";

    /** The columns, in order, written to the CSV export. */
    private static final String[] COLUMNS =
            {"timestamp", "event", "request_id", "api_key_id", "associated_object", "client_ip_address", "source", "details"};

    

    

    private final MongoCollection<Document> collection;

    public MongoAuditLogService(final MongoClient mongoClient) {
        this.collection = mongoClient.getDatabase(DATABASE).getCollection(COLLECTION);
    }

    

    

    

    

    

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

        final List<Bson> conditions = new ArrayList<>(List.of(
                Filters.gte("timestamp", from),
                Filters.lt("timestamp", toExclusive)));
        if (cursor != null) {
            // After the cursor in (timestamp, _id) descending order. The plain bound lets the index narrow the
            // range before the tie on the cursor's millisecond is broken by id.
            conditions.add(Filters.lte("timestamp", cursor.timestamp()));
            conditions.add(Filters.or(
                    Filters.lt("timestamp", cursor.timestamp()),
                    Filters.and(Filters.eq("timestamp", cursor.timestamp()), Filters.lt("_id", MongoIds.objectId(cursor.id())))));
        }
        final Bson query = Filters.and(conditions);

        final StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", COLUMNS)).append('\n');

        // One more than the cap is read, only to learn whether the cap cut the export short.
        int rows = 0;
        boolean truncated = false;
        Document last = null;
        for (final Document document : collection.find(query).sort(Sorts.descending("timestamp", "_id")).skip(offset).limit(limit + 1)) {
            if (rows == limit) {
                truncated = true;
                break;
            }
            last = document;
            for (int i = 0; i < COLUMNS.length; i++) {
                if (i > 0) {
                    csv.append(',');
                }
                csv.append(csvEscape(format(document.get(COLUMNS[i]))));
            }
            csv.append('\n');
            rows++;
        }

        final String nextCursor = truncated
                ? new ExportCursor(last.getDate("timestamp"), MongoIds.hex(last.getObjectId("_id"))).encode() : null;

        return new CsvExport(csv.toString().getBytes(StandardCharsets.UTF_8), rows, truncated, effectiveZone, offset,
                nextCursor);

    }

    

        @Override
    public List<AuditEvent> find(final String principalId, final String event, final Date from,
                               final Date toExclusive, final int offset, final int limit, final boolean descending) {

        final List<AuditEvent> events = new ArrayList<>();

        collection.find(filter(principalId, event, from, toExclusive))
                .sort(descending ? Sorts.descending("timestamp", "_id") : Sorts.ascending("timestamp", "_id"))
                .skip(offset)
                .limit(limit)
                .forEach(document -> events.add(toEvent(document)));

        return events;

    }

        @Override
    public long count(final String principalId, final String event, final Date from, final Date toExclusive) {
        return collection.countDocuments(filter(principalId, event, from, toExclusive));
    }

    private static Bson filter(final String principalId, final String event, final Date from, final Date toExclusive) {

        final List<Bson> conditions = new ArrayList<>();

        if (principalId != null) {
            conditions.add(Filters.eq("api_key_id", MongoIds.objectId(principalId)));
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

    private static AuditEvent toEvent(final Document document) {
        return new AuditEvent(document.getDate("timestamp"), document.getString("event"),
                document.getString("request_id"), text(document.get("api_key_id")),
                text(document.get("associated_object")), document.getString("client_ip_address"),
                document.getString("source"), document.getString("details"));
    }

    /** A stored id or value as text: an ObjectId as its hex, anything else as itself. */
    private static String text(final Object value) {
        return value == null ? null : value.toString();
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
    /**
     * Writes a value as one CSV cell. A value a spreadsheet would run as a formula is prefixed with an
     * apostrophe first: the client IP column comes from a request header, so a caller controls it.
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
