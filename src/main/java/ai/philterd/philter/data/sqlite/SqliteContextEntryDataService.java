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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores context entries in SQLite, one row per token and context. */
public class SqliteContextEntryDataService extends StoredContextEntryDataService {

    private final SqliteDatabase database;

    public SqliteContextEntryDataService(final SqliteDatabase database, final ContextCache contextCache) {
        super(contextCache);
        this.database = database;
    }

    /** With contexts that hold at most {@code capacity} entries, for tests. */
    SqliteContextEntryDataService(final SqliteDatabase database, final ContextCache contextCache, final int capacity) {
        super(contextCache, capacity);
        this.database = database;
    }

    @Override
    public SqliteContextEntryDataService withCache(final ContextCache contextCache) {
        return new SqliteContextEntryDataService(database, contextCache, capacity());
    }

    @Override
    protected String findUsername(final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT username FROM users WHERE id = ?",
                row -> row.string("username"), userId));
    }

    private static ContextEntryEntity read(final Sql.Row row) throws SQLException {
        final ContextEntryEntity entry = new ContextEntryEntity();
        entry.setId(row.string("id"));
        entry.setUserId(row.string("user_id"));
        entry.setContextName(row.string("context_name"));
        entry.setTokenHash(row.string("token_hash"));
        entry.setReplacement(row.string("replacement"));
        entry.setReplacementUuid(row.bool("replacement_uuid"));
        entry.setReads(row.longValue("reads"));
        entry.setTimestamp(row.date("timestamp"));
        entry.setFilterType(row.string("filter_type"));
        return entry;
    }

    private static int insert(final Connection connection, final ContextEntryEntity entry, final String conflict) throws SQLException {
        return Sql.update(connection, "INSERT INTO context_entries (id, user_id, context_name, token_hash, replacement, "
                + "replacement_uuid, reads, timestamp, filter_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)" + conflict,
                RecordIds.newId(), entry.getUserId(), entry.getContextName(), entry.getTokenHash(), entry.getReplacement(),
                entry.isReplacementUuid(), entry.getReads(), entry.getTimestamp(), entry.getFilterType());
    }

    private static ContextEntryEntity byTokenHash(final Connection connection, final String userId, final String contextName,
                                                  final String tokenHash) throws SQLException {
        return Sql.first(connection, "SELECT * FROM context_entries WHERE user_id = ? AND context_name = ? AND token_hash = ?",
                SqliteContextEntryDataService::read, userId, contextName, tokenHash);
    }

    @Override
    protected ContextEntryEntity insertIfAbsent(final ContextEntryEntity entry) {
        return database.write(connection -> {
            insert(connection, entry, " ON CONFLICT (user_id, context_name, token_hash) DO NOTHING");
            return byTokenHash(connection, entry.getUserId(), entry.getContextName(), entry.getTokenHash());
        });
    }

    @Override
    protected ContextEntryEntity findByTokenHash(final String userId, final String contextName, final String tokenHash) {
        return database.read(connection -> byTokenHash(connection, userId, contextName, tokenHash));
    }

    @Override
    protected void insert(final ContextEntryEntity entry) {
        database.write(connection -> insert(connection, entry, ""));
    }

    @Override
    protected void updateReplacement(final String userId, final String contextName, final String tokenHash,
                                     final String replacement, final String filterType, final boolean replacementUuid) {
        database.write(connection -> Sql.update(connection, "UPDATE context_entries SET replacement = ?, filter_type = ?, "
                + "replacement_uuid = ? WHERE user_id = ? AND context_name = ? AND token_hash = ?",
                replacement, filterType, replacementUuid, userId, contextName, tokenHash));
    }

    @Override
    protected ContextEntryEntity deleteById(final String id, final String userId, final String contextName) {
        return database.write(connection -> {
            final ContextEntryEntity entry = Sql.first(connection, "SELECT * FROM context_entries WHERE id = ? AND user_id = ?"
                    + (contextName == null ? "" : " AND context_name = ?"), SqliteContextEntryDataService::read,
                    contextName == null ? new Object[]{id, userId} : new Object[]{id, userId, contextName});
            if (entry != null) {
                Sql.update(connection, "DELETE FROM context_entries WHERE id = ?", id);
            }
            return entry;
        });
    }

    @Override
    protected ContextEntryEntity findLeastRead(final String userId, final String contextName, final String keepId) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM context_entries WHERE user_id = ? AND "
                + "context_name = ? AND id IS NOT ? ORDER BY reads ASC, timestamp ASC, seq ASC LIMIT 1",
                SqliteContextEntryDataService::read, userId, contextName, keepId));
    }

    @Override
    protected void deleteContext(final String userId, final String contextName) {
        database.write(connection -> Sql.update(connection, "DELETE FROM context_entries WHERE user_id = ? AND context_name = ?",
                userId, contextName));
    }

    @Override
    public void incrementReads(final String id) {
        database.write(connection -> Sql.update(connection, "UPDATE context_entries SET reads = reads + 1 WHERE id = ?", id));
    }

    @Override
    public List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName, final int offset,
                                                              final int limit, final Listings.Sort sort) {
        return database.read(connection -> Sql.query(connection, "SELECT * FROM context_entries WHERE user_id = ? AND "
                + "context_name = ?" + Sql.orderBy(sort) + " LIMIT ? OFFSET ?", SqliteContextEntryDataService::read,
                userId, contextName, Math.min(limit, MAX_LIMIT), Math.max(0, offset)));
    }

    @Override
    public int countByUserIdAndContext(final String userId, final String contextName) {
        return (int) (long) database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM context_entries WHERE user_id = ? AND context_name = ?", userId, contextName));
    }

    @Override
    public List<ContextEntryEntity> findAllByUserIdAndContext(final String userId, final String contextName) {
        return database.read(connection -> Sql.query(connection, "SELECT * FROM context_entries WHERE user_id = ? AND "
                + "context_name = ? ORDER BY timestamp DESC, seq DESC", SqliteContextEntryDataService::read, userId, contextName));
    }

    @Override
    public boolean containsReplacement(final String userId, final String contextName, final String replacement) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM context_entries WHERE "
                + "user_id = ? AND context_name = ? AND replacement = ?", userId, contextName, replacement) > 0);
    }

    @Override
    public Map<String, Long> getFilterTypeCounts(final String contextName, final String userId) {
        final Map<String, Long> counts = new HashMap<>();
        database.read(connection -> Sql.query(connection, "SELECT filter_type, COUNT(*) AS n FROM context_entries WHERE "
                + "user_id = ? AND context_name = ? GROUP BY filter_type", row -> counts.put(row.string("filter_type"),
                row.longValue("n")), userId, contextName));
        return counts;
    }

}
