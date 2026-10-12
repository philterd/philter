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
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.StoredApiKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Stores API keys in SQLite, one row per key, kept after it is revoked. */
public class SqliteApiKeyDataService extends StoredApiKeyDataService {

    private static final String DUE = "session = 1 AND deleted = 0 AND (expires_at <= ? OR idle_expires_at <= ?)";

    /** Ends a key, removing the sign-in details kept only while a session lasts. */
    private static final String ENDED = "deleted = 1, deleted_at = ?, client_address = NULL, user_agent = NULL";

    private final SqliteDatabase database;

    public SqliteApiKeyDataService(final SqliteDatabase database, final AuditEventPublisher auditEventPublisher,
                                   final ApiKeyCache apiKeyCache) {
        super(auditEventPublisher, apiKeyCache);
        this.database = database;
    }

    private static ApiKeyEntity read(final Sql.Row row) throws SQLException {
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(row.string("id"));
        key.setUserId(row.string("user_id"));
        key.setApiKeyHash(row.string("api_key_hash"));
        key.setApiKeyPrefix(row.string("api_key_prefix"));
        key.setDeleted(row.bool("deleted"));
        key.setDeletedAt(row.date("deleted_at"));
        key.setTimestamp(row.date("timestamp"));
        key.setBootstrap(row.bool("bootstrap"));
        key.setSession(row.bool("session"));
        key.setExpiresAt(row.date("expires_at"));
        key.setIdleTimeoutSeconds(row.intValue("idle_timeout_seconds"));
        key.setIdleExpiresAt(row.date("idle_expires_at"));
        key.setLastUsedAt(row.date("last_used_at"));
        key.setPasswordChangeOnly(row.bool("password_change_only"));
        key.setMfaEnrollmentOnly(row.bool("mfa_enrollment_only"));
        key.setClientAddress(row.string("client_address"));
        key.setUserAgent(row.string("user_agent"));
        final String scopes = row.string("scopes");
        key.setScopes(scopes == null || scopes.isEmpty() ? new LinkedHashSet<>() : new LinkedHashSet<>(Arrays.asList(scopes.split(","))));
        return key;
    }

    @Override
    protected String insert(final ApiKeyEntity key) {
        final String id = RecordIds.newId();
        database.write(connection -> Sql.update(connection, "INSERT INTO api_keys (id, user_id, api_key_hash, api_key_prefix, "
                + "deleted, deleted_at, timestamp, bootstrap, session, expires_at, idle_timeout_seconds, idle_expires_at, "
                + "last_used_at, password_change_only, mfa_enrollment_only, client_address, user_agent, scopes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, key.getUserId(), key.getApiKeyHash(), key.getApiKeyPrefix(), key.isDeleted(), key.getDeletedAt(),
                key.getTimestamp(), key.isBootstrap(), key.isSession(), key.getExpiresAt(), key.getIdleTimeoutSeconds(),
                key.getIdleExpiresAt(), key.getLastUsedAt(), key.isPasswordChangeOnly(), key.isMfaEnrollmentOnly(),
                key.getClientAddress(), key.getUserAgent(), scopes(key.getScopes())));
        return id;
    }

    private static String scopes(final Set<String> scopes) {
        return scopes == null ? "" : String.join(",", scopes);
    }

    @Override
    protected boolean existsByHash(final String apiKeyHash) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM api_keys WHERE api_key_hash = ?",
                apiKeyHash) > 0);
    }

    @Override
    protected ApiKeyEntity findActiveByHash(final String apiKeyHash) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM api_keys WHERE api_key_hash = ? AND deleted = 0",
                SqliteApiKeyDataService::read, apiKeyHash));
    }

    @Override
    protected boolean isEndedSessionByHash(final String apiKeyHash) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM api_keys WHERE api_key_hash = ? "
                + "AND session = 1 AND deleted = 1", apiKeyHash) > 0);
    }

    @Override
    public ApiKeyEntity findOneById(final String id) {
        if (id == null) {
            return null;
        }
        return database.read(connection -> Sql.first(connection, "SELECT * FROM api_keys WHERE id = ? AND deleted = 0",
                SqliteApiKeyDataService::read, id));
    }

    private static String active(final Boolean session) {
        return "deleted = 0 AND user_id = ?" + (session == null ? "" : session ? " AND session = 1" : " AND session = 0");
    }

    @Override
    public List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session,
                                               final boolean descending) {
        final String direction = descending ? " DESC" : " ASC";
        return database.read(connection -> Sql.query(connection, "SELECT * FROM api_keys WHERE " + active(session)
                + " ORDER BY timestamp" + direction + ", seq" + direction + " LIMIT ? OFFSET ?",
                SqliteApiKeyDataService::read, userId, limit, offset));
    }

    @Override
    public int countBySession(final String userId, final Boolean session) {
        return (int) (long) database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM api_keys WHERE "
                + active(session), userId));
    }

    @Override
    public int count(final String userId, final boolean includeDeleted) {
        final List<String> conditions = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        if (!includeDeleted) {
            conditions.add("deleted = 0");
        }
        if (userId != null) {
            conditions.add("user_id = ?");
            params.add(userId);
        }
        return (int) (long) database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM api_keys"
                + Sql.where(conditions.toArray(new String[0])), params.toArray()));
    }

    @Override
    protected boolean setScopes(final String id, final String userId, final Set<String> scopes) {
        return database.write(connection -> Sql.update(connection, "UPDATE api_keys SET scopes = ? WHERE id = ? AND user_id = ? "
                + "AND deleted = 0", scopes(scopes), id, userId)) == 1;
    }

    @Override
    protected void end(final List<String> ids, final String userId, final Date when) {
        database.write(connection -> {
            for (final String id : ids) {
                Sql.update(connection, "UPDATE api_keys SET " + ENDED + " WHERE id = ? AND user_id = ? AND deleted = 0",
                        when, id, userId);
            }
            return null;
        });
    }

    @Override
    protected boolean touch(final String id, final Date now, final Date idleExpiresAt) {
        return database.write(connection -> Sql.update(connection, "UPDATE api_keys SET last_used_at = ?, idle_expires_at = ? "
                + "WHERE id = ? AND session = 1 AND deleted = 0 AND expires_at > ? AND idle_expires_at > ?",
                now, idleExpiresAt, id, now, now)) == 1;
    }

    @Override
    protected List<ApiKeyEntity> findDueSessionKeys(final String sessionKeyId, final Date now) {
        return database.read(connection -> sessionKeyId == null
                ? Sql.query(connection, "SELECT * FROM api_keys WHERE " + DUE, SqliteApiKeyDataService::read, now, now)
                : Sql.query(connection, "SELECT * FROM api_keys WHERE id = ? AND " + DUE, SqliteApiKeyDataService::read,
                        sessionKeyId, now, now));
    }

    @Override
    protected boolean endIfDue(final String id, final Date now) {
        return database.write(connection -> Sql.update(connection, "UPDATE api_keys SET " + ENDED + " WHERE id = ? AND " + DUE,
                now, id, now, now)) == 1;
    }

    @Override
    protected List<ApiKeyEntity> findActiveSessionKeys(final String userId, final String keepKeyId) {
        return database.read(connection -> keepKeyId == null
                ? Sql.query(connection, "SELECT * FROM api_keys WHERE user_id = ? AND session = 1 AND deleted = 0",
                        SqliteApiKeyDataService::read, userId)
                : Sql.query(connection, "SELECT * FROM api_keys WHERE user_id = ? AND session = 1 AND deleted = 0 AND id <> ?",
                        SqliteApiKeyDataService::read, userId, keepKeyId));
    }

}
