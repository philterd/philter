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
package ai.philterd.philter.data.sqlite;

import ai.philterd.philter.audit.StoredAuditLogService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Reads the audit log from SQLite. Events are ordered by timestamp and then record id, which is what an export
 * cursor names.
 */
public class SqliteAuditLogService extends StoredAuditLogService {

    private final SqliteDatabase database;

    public SqliteAuditLogService(final SqliteDatabase database) {
        this.database = database;
    }

    private static AuditEvent event(final Sql.Row row) throws SQLException {
        return new AuditEvent(row.date("timestamp"), row.string("event"), row.string("request_id"),
                row.string("api_key_id"), row.string("associated_object"), row.string("client_ip_address"),
                row.string("source"), row.string("details"));
    }

    @Override
    protected List<StoredEvent> findForExport(final Date from, final Date toExclusive, final ExportCursor cursor,
                                              final int offset, final int limit) {
        final List<Object> params = new ArrayList<>(List.of(from, toExclusive));
        String after = null;
        if (cursor != null) {
            after = "timestamp < ? OR (timestamp = ? AND id < ?)";
            params.add(cursor.timestamp());
            params.add(cursor.timestamp());
            params.add(cursor.id());
        }
        params.add(limit);
        params.add(offset);
        final String where = Sql.where("timestamp >= ?", "timestamp < ?", after);
        return database.read(connection -> Sql.query(connection, "SELECT * FROM audit_events" + where
                        + " ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?",
                row -> new StoredEvent(row.string("id"), event(row)), params.toArray()));
    }

    private static String filter(final String principalId, final String event, final Date from, final Date toExclusive,
                                 final List<Object> params) {
        String principal = null;
        String type = null;
        String start = null;
        String end = null;
        if (principalId != null) {
            principal = "api_key_id = ?";
            params.add(principalId);
        }
        if (event != null && !event.isBlank()) {
            type = "event = ?";
            params.add(event);
        }
        if (from != null) {
            start = "timestamp >= ?";
            params.add(from);
        }
        if (toExclusive != null) {
            end = "timestamp < ?";
            params.add(toExclusive);
        }
        return Sql.where(principal, type, start, end);
    }

    @Override
    public List<AuditEvent> find(final String principalId, final String event, final Date from, final Date toExclusive,
                                 final int offset, final int limit, final boolean descending) {
        final List<Object> params = new ArrayList<>();
        final String where = filter(principalId, event, from, toExclusive, params);
        params.add(limit);
        params.add(offset);
        final String direction = descending ? " DESC" : " ASC";
        return database.read(connection -> Sql.query(connection, "SELECT * FROM audit_events" + where
                + " ORDER BY timestamp" + direction + ", id" + direction + " LIMIT ? OFFSET ?",
                SqliteAuditLogService::event, params.toArray()));
    }

    @Override
    public long count(final String principalId, final String event, final Date from, final Date toExclusive) {
        final List<Object> params = new ArrayList<>();
        final String where = filter(principalId, event, from, toExclusive, params);
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM audit_events" + where,
                params.toArray()));
    }

}
