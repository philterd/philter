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

import ai.philterd.philter.audit.StoredAuditLogService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** MongoDB implementation of {@link AuditLogService}, reading the {@code audit_events} collection. */
public class MongoAuditLogService extends StoredAuditLogService {

    private static final String DATABASE = "philter";
    private static final String COLLECTION = "audit_events";

    private final MongoCollection<Document> collection;

    public MongoAuditLogService(final MongoClient mongoClient) {
        this.collection = mongoClient.getDatabase(DATABASE).getCollection(COLLECTION);
    }

    @Override
    protected List<StoredEvent> findForExport(final Date from, final Date toExclusive, final ExportCursor cursor,
                                              final int offset, final int limit) {

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

        final List<StoredEvent> events = new ArrayList<>();
        for (final Document document : collection.find(Filters.and(conditions))
                .sort(Sorts.descending("timestamp", "_id")).skip(offset).limit(limit)) {
            events.add(new StoredEvent(MongoIds.hex(document.getObjectId("_id")), toEvent(document)));
        }
        return events;

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

}
