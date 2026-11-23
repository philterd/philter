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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredContextDataService;
import ai.philterd.philter.services.vectors.VectorServices;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stores contexts in SQLite, one row per context, with the context's values as a JSON object in its row. */
public class SqliteContextDataService extends StoredContextDataService {

    private static final Gson GSON = new Gson();
    private static final Type VALUES = new TypeToken<LinkedHashMap<String, String>>() { }.getType();

    private final SqliteDatabase database;

    public SqliteContextDataService(final SqliteDatabase database, final AuditEventPublisher auditEventPublisher,
                                    final ContextEntryDataService contextEntryService, final VectorServices vectorServices) {
        super(auditEventPublisher, contextEntryService, vectorServices);
        this.database = database;
    }

    private static ContextEntity read(final Sql.Row row) throws SQLException {
        final ContextEntity context = new ContextEntity();
        context.setId(row.string("id"));
        context.setUserId(row.string("user_id"));
        context.setContextName(row.string("context_name"));
        final Integer maxSize = row.integer("max_size");
        context.setMaxSize(maxSize == null ? ContextEntity.MAX_CONTEXT_SIZE : maxSize);
        context.setDisambiguation(row.bool("disambiguation"));
        final String scope = row.string("disambiguation_scope");
        context.setDisambiguationScope(scope == null ? ContextEntity.DISAMBIGUATION_SCOPE_DOCUMENT : scope);
        context.setLedger(row.bool("ledger"));
        context.setSlot(row.integer("slot"));
        final Integer ttl = row.integer("ttl_in_hours");
        context.setTtlInHours(ttl == null ? ContextEntity.DEFAULT_TTL_IN_HOURS : ttl);
        context.setTimestamp(row.date("timestamp"));
        context.setValues(values(row.string("context_values")));
        return context;
    }

    private static Map<String, String> values(final String json) {
        return json == null ? new LinkedHashMap<>() : GSON.fromJson(json, VALUES);
    }

    private static String storedValues(final Connection connection, final String userId, final String contextName)
            throws SQLException {
        return Sql.first(connection, "SELECT context_values FROM contexts WHERE user_id = ? AND context_name = ?",
                row -> {
                    final String json = row.string("context_values");
                    return json == null ? "{}" : json;
                }, userId, contextName);
    }

    @Override
    protected String insertValueIfAbsent(final String userId, final String contextName, final String key, final String value) {
        // A write holds the database's write lock from its start, so the read and the update are atomic.
        return database.write(connection -> {
            final String json = storedValues(connection, userId, contextName);
            if (json == null) {
                return null;
            }
            final Map<String, String> values = values(json);
            final String existing = values.get(key);
            if (existing != null) {
                return existing;
            }
            values.put(key, value);
            Sql.update(connection, "UPDATE contexts SET context_values = ? WHERE user_id = ? AND context_name = ?",
                    GSON.toJson(values), userId, contextName);
            return value;
        });
    }

    @Override
    protected boolean updateValue(final String userId, final String contextName, final String key, final String value) {
        return database.write(connection -> {
            final String json = storedValues(connection, userId, contextName);
            if (json == null) {
                return false;
            }
            final Map<String, String> values = values(json);
            values.put(key, value);
            Sql.update(connection, "UPDATE contexts SET context_values = ? WHERE user_id = ? AND context_name = ?",
                    GSON.toJson(values), userId, contextName);
            return true;
        });
    }

    @Override
    protected String insert(final ContextEntity context) {
        final String id = RecordIds.newId();
        database.write(connection -> Sql.update(connection, "INSERT INTO contexts (id, user_id, context_name, max_size, "
                + "disambiguation, disambiguation_scope, ledger, slot, ttl_in_hours, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, context.getUserId(), context.getContextName(), context.getMaxSize(), context.isDisambiguation(),
                context.getDisambiguationScope(), context.isLedger(), context.getSlot(), context.getTtlInHours(),
                context.getTimestamp()));
        return id;
    }

    @Override
    protected Set<Integer> usedSlots(final String userId) {
        return database.read(connection -> new HashSet<>(Sql.query(connection,
                "SELECT slot FROM contexts WHERE user_id = ? AND slot IS NOT NULL", row -> row.intValue("slot"), userId)));
    }

    @Override
    protected void updateSettings(final String userId, final String contextName, final Boolean disambiguation,
                                  final String disambiguationScope, final Boolean ledger) {
        final List<String> sets = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (disambiguation != null) {
            sets.add("disambiguation = ?");
            params.add(disambiguation);
        }
        if (disambiguationScope != null) {
            sets.add("disambiguation_scope = ?");
            params.add(disambiguationScope);
        }
        if (ledger != null) {
            sets.add("ledger = ?");
            params.add(ledger);
        }
        params.add(userId);
        params.add(contextName);
        database.write(connection -> Sql.update(connection, "UPDATE contexts SET " + String.join(", ", sets)
                + " WHERE user_id = ? AND context_name = ?", params.toArray()));
    }

    @Override
    protected void delete(final String userId, final String contextName) {
        database.write(connection -> Sql.update(connection, "DELETE FROM contexts WHERE user_id = ? AND context_name = ?",
                userId, contextName));
    }

    @Override
    public ContextEntity findOneByNameAndUserId(final String contextName, final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM contexts WHERE user_id = ? AND context_name = ?",
                SqliteContextDataService::read, userId, contextName));
    }

    @Override
    public Listings.Page<ContextEntity> list(final String userId, final String q, final Listings.Sort sort, final int offset,
                                             final int limit) {
        final List<Object> params = new ArrayList<>();
        String owner = null;
        if (userId != null) {
            owner = "user_id = ?";
            params.add(userId);
        }
        final String where = Sql.where(owner, Sql.contains("context_name", q, params));
        return database.read(connection -> Sql.page(connection, "contexts", where, params, sort, offset, limit,
                SqliteContextDataService::read));
    }

    @Override
    public int count(final String userId) {
        return (int) (long) database.read(connection -> userId == null
                ? Sql.count(connection, "SELECT COUNT(*) AS n FROM contexts")
                : Sql.count(connection, "SELECT COUNT(*) AS n FROM contexts WHERE user_id = ?", userId));
    }

}
