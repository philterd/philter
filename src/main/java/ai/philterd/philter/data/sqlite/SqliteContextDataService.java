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

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stores contexts in SQLite, one row per context. */
public class SqliteContextDataService extends StoredContextDataService {

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
        return context;
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
