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
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredUserService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores users in SQLite, one row per user, kept when the user is deactivated. */
public class SqliteUserService extends StoredUserService {

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqliteUserService(final SqliteDatabase database, final EncryptionService encryptionService,
                             final AuditEventPublisher auditEventPublisher) {
        super(auditEventPublisher);
        this.database = database;
        this.encryptionService = encryptionService;
    }

    /** A secret field as stored: empty for none, read back as null. */
    private String open(final Sql.Row row, final String column) throws SQLException {
        final String value = row.string(column);
        return value == null || value.isEmpty() ? null : SecretFields.open(encryptionService, value, row.string(column + "_key"));
    }

    private SecretFields.Sealed seal(final String value, final String userId) {
        return value == null || value.isEmpty() ? new SecretFields.Sealed("", "")
                : SecretFields.seal(encryptionService, value, userId == null ? "" : userId);
    }

    private UserEntity read(final Sql.Row row) throws SQLException {
        final UserEntity user = new UserEntity();
        user.setId(row.string("id"));
        user.setCreatedAt(row.date("created_at"));
        user.setUsername(row.string("username"));
        user.setEmail(row.string("email"));
        user.setRole(row.string("role"));
        user.setFpeKey(open(row, "fpe_key"));
        user.setWebhookUrl(row.string("webhook_url"));
        user.setWebhookSecret(open(row, "webhook_secret"));
        user.setPassword(row.string("password"));
        user.setPasswordChangeRequired(row.bool("password_change_required"));
        user.setMfaEnabled(row.bool("mfa_enabled"));
        user.setMfaSecret(open(row, "mfa_secret"));
        user.setMfaPendingSecret(open(row, "mfa_pending_secret"));
        user.setMfaFailedAttempts(row.intValue("mfa_failed_attempts"));
        user.setMfaLocked(row.bool("mfa_locked"));
        user.setMfaLastUsedTimeStep(row.longValue("mfa_last_used_time_step"));
        user.setDeactivated(row.bool("deactivated"));
        user.setDeactivatedAt(row.date("deactivated_at"));
        return user;
    }

    @Override
    protected String insert(final UserEntity user) {
        final String id = RecordIds.newId();
        // Secrets are sealed for the user's id, as an existing user's are.
        final SecretFields.Sealed fpeKey = seal(user.getFpeKey(), id);
        final SecretFields.Sealed webhookSecret = seal(user.getWebhookSecret(), id);
        final SecretFields.Sealed mfaSecret = seal(user.getMfaSecret(), id);
        final SecretFields.Sealed mfaPending = seal(user.getMfaPendingSecret(), id);
        database.write(connection -> Sql.update(connection, "INSERT INTO users (id, username, email, role, fpe_key, fpe_key_key, "
                + "webhook_url, webhook_secret, webhook_secret_key, password, password_change_required, mfa_enabled, mfa_secret, "
                + "mfa_secret_key, mfa_pending_secret, mfa_pending_secret_key, mfa_failed_attempts, mfa_locked, "
                + "mfa_last_used_time_step, deactivated, deactivated_at, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, user.getUsername(), user.getEmail(), user.getRole(), fpeKey.ciphertext(), fpeKey.key(),
                user.getWebhookUrl(), webhookSecret.ciphertext(), webhookSecret.key(), user.getPassword(),
                user.isPasswordChangeRequired(), user.isMfaEnabled(), mfaSecret.ciphertext(), mfaSecret.key(),
                mfaPending.ciphertext(), mfaPending.key(), user.getMfaFailedAttempts(), user.isMfaLocked(),
                user.getMfaLastUsedTimeStep(), user.isDeactivated(), user.getDeactivatedAt(), new Date()));
        return id;
    }

    /** The SET clause and its values for a field group. */
    private List<Object> set(final UserEntity user, final Field field, final List<String> columns) {
        final List<Object> values = new ArrayList<>();
        switch (field) {
            case ROLE -> {
                columns.add("role");
                values.add(user.getRole());
            }
            case EMAIL -> {
                columns.addAll(List.of("username", "email"));
                values.addAll(java.util.Arrays.asList(user.getUsername(), user.getEmail()));
            }
            case DEACTIVATION -> {
                columns.addAll(List.of("deactivated", "deactivated_at"));
                values.addAll(java.util.Arrays.asList(user.isDeactivated(), user.getDeactivatedAt()));
            }
            case WEBHOOK -> {
                final SecretFields.Sealed secret = seal(user.getWebhookSecret(), user.getId());
                columns.addAll(List.of("webhook_url", "webhook_secret", "webhook_secret_key"));
                values.addAll(java.util.Arrays.asList(user.getWebhookUrl(), secret.ciphertext(), secret.key()));
            }
            case MFA_PENDING_SECRET -> {
                final SecretFields.Sealed pending = seal(user.getMfaPendingSecret(), user.getId());
                columns.addAll(List.of("mfa_pending_secret", "mfa_pending_secret_key"));
                values.addAll(List.of(pending.ciphertext(), pending.key()));
            }
            case MFA -> {
                final SecretFields.Sealed secret = seal(user.getMfaSecret(), user.getId());
                final SecretFields.Sealed pending = seal(user.getMfaPendingSecret(), user.getId());
                columns.addAll(List.of("mfa_enabled", "mfa_secret", "mfa_secret_key", "mfa_pending_secret",
                        "mfa_pending_secret_key", "mfa_failed_attempts", "mfa_locked", "mfa_last_used_time_step"));
                values.addAll(List.of(user.isMfaEnabled(), secret.ciphertext(), secret.key(), pending.ciphertext(),
                        pending.key(), user.getMfaFailedAttempts(), user.isMfaLocked(), user.getMfaLastUsedTimeStep()));
            }
        }
        return values;
    }

    private int update(final UserEntity user, final Field field, final String condition, final Object... conditionValues) {
        final List<String> columns = new ArrayList<>();
        final List<Object> params = set(user, field, columns);
        params.add(user.getId());
        params.addAll(List.of(conditionValues));
        final List<String> assignments = columns.stream().map(column -> column + " = ?").toList();
        final String sql = "UPDATE users SET " + String.join(", ", assignments) + " WHERE id = ?" + condition;
        return database.write(connection -> Sql.update(connection, sql, params.toArray()));
    }

    @Override
    protected boolean write(final UserEntity user, final Field field) {
        return field == Field.DEACTIVATION
                ? update(user, field, " AND deactivated <> ?", user.isDeactivated()) == 1
                : update(user, field, "") == 1;
    }

    private static final String ACTIVE_ADMIN = "lower(role) = 'admin' AND deactivated = 0";

    @Override
    protected AdministratorChange writeKeepingAnAdministrator(final UserEntity user, final Field field) {
        final List<String> columns = new ArrayList<>();
        final List<Object> params = set(user, field, columns);
        params.add(user.getId());
        String condition = "";
        if (field == Field.DEACTIVATION) {
            condition = " AND deactivated <> ?";
            params.add(user.isDeactivated());
        }
        final String sql = "UPDATE users SET " + String.join(", ", columns.stream().map(column -> column + " = ?").toList())
                + " WHERE id = ?" + condition;
        // One transaction, which holds SQLite's write lock from its start, so no other change runs between the
        // check and the write.
        return database.write(connection -> {
            final boolean activeAdmin = Sql.count(connection, "SELECT COUNT(*) AS n FROM users WHERE id = ? AND "
                    + ACTIVE_ADMIN, user.getId()) == 1;
            if (activeAdmin && Sql.count(connection, "SELECT COUNT(*) AS n FROM users WHERE id <> ? AND " + ACTIVE_ADMIN,
                    user.getId()) == 0) {
                return AdministratorChange.LAST_ADMINISTRATOR;
            }
            return Sql.update(connection, sql, params.toArray()) == 1 ? AdministratorChange.WRITTEN
                    : AdministratorChange.UNCHANGED;
        });
    }

    @Override
    protected Boolean findDeactivated(final String userId) {
        return database.read(connection -> Sql.first(connection, "SELECT deactivated FROM users WHERE id = ?",
                row -> row.bool("deactivated"), userId));
    }

    @Override
    protected boolean enrollMfa(final UserEntity user) {
        return update(user, Field.MFA, " AND mfa_enabled = 0") == 1;
    }

    @Override
    protected boolean acceptMfaStep(final String userId, final long step) {
        return database.write(connection -> Sql.update(connection, "UPDATE users SET mfa_last_used_time_step = ?, "
                + "mfa_failed_attempts = 0 WHERE id = ? AND mfa_enabled = 1 AND mfa_locked = 0 AND mfa_last_used_time_step < ?",
                step, userId, step)) == 1;
    }

    @Override
    protected int countMfaFailure(final String userId) {
        return database.write(connection -> {
            if (Sql.update(connection, "UPDATE users SET mfa_failed_attempts = mfa_failed_attempts + 1 WHERE id = ? "
                    + "AND mfa_enabled = 1", userId) == 0) {
                return 0;
            }
            return Sql.first(connection, "SELECT mfa_failed_attempts FROM users WHERE id = ?",
                    row -> row.intValue("mfa_failed_attempts"), userId);
        });
    }

    @Override
    protected boolean lockMfa(final String userId) {
        return database.write(connection -> Sql.update(connection, "UPDATE users SET mfa_locked = 1 WHERE id = ? "
                + "AND mfa_locked = 0", userId)) == 1;
    }

    @Override
    protected boolean unlockMfa(final String userId) {
        return database.write(connection -> Sql.update(connection, "UPDATE users SET mfa_locked = 0, mfa_failed_attempts = 0 "
                + "WHERE id = ? AND mfa_locked = 1", userId)) == 1;
    }

    @Override
    protected boolean clearSignInLock(final String userId, final long nowMillis) {
        return database.write(connection -> Sql.update(connection, "UPDATE users SET sign_in_locked_until = NULL, "
                + "sign_in_failures = 0, sign_in_window_until = NULL WHERE id = ? AND sign_in_locked_until > ?",
                userId, nowMillis)) == 1;
    }

    @Override
    protected boolean writePassword(final String userId, final String expectedHash, final String newHash,
                                    final boolean changeRequired) {
        return database.write(connection -> Sql.update(connection, "UPDATE users SET password = ?, password_change_required = ? "
                + "WHERE id = ? AND password IS ?", newHash, changeRequired, userId, expectedHash)) == 1;
    }

    private UserEntity first(final String where, final Object... params) {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM users WHERE " + where, this::read, params));
    }

    @Override
    public UserEntity findByUsername(final String username) {
        return first("username = ? AND deactivated = 0", username);
    }

    @Override
    public UserEntity findAnyByUsername(final String username) {
        return first("username = ?", username);
    }

    @Override
    public UserEntity findOneById(final String id) {
        return first("id = ?", id);
    }

    @Override
    public Map<String, String> findUsernamesByIds(final Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, String> usernames = new HashMap<>();
        database.read(connection -> {
            for (final String id : ids) {
                final String username = Sql.first(connection, "SELECT username FROM users WHERE id = ?",
                        row -> row.string("username"), id);
                if (username != null) {
                    usernames.put(id, username);
                }
            }
            return null;
        });
        return usernames;
    }

    @Override
    public Listings.Page<UserEntity> list(final String q, final String role, final Boolean active, final Listings.Sort sort,
                                          final int offset, final int limit) {
        final List<Object> params = new ArrayList<>();
        final String search = Sql.contains("username", q, params);
        String roleCondition = null;
        if (role != null) {
            roleCondition = "role = ?";
            params.add(role);
        }
        final String activeCondition = active == null ? null : active ? "deactivated = 0" : "deactivated = 1";
        final String where = Sql.where(search, roleCondition, activeCondition);
        return database.read(connection -> Sql.page(connection, "users", where, params, sort, offset, limit, this::read));
    }

    @Override
    public int count(final boolean includeDeactivated) {
        return (int) (long) database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM users"
                + (includeDeactivated ? "" : " WHERE deactivated = 0")));
    }

    @Override
    public long countActiveAdmins() {
        return database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM users WHERE " + ACTIVE_ADMIN));
    }

    @Override
    public long beginSignInAttempt(final String userId, final long nowMillis, final long windowMillis) {
        // One write: SQLite runs writes one at a time, and every SET reads the row as it was.
        return database.write(connection -> {
            Sql.update(connection, "UPDATE users SET sign_in_failures = CASE WHEN sign_in_window_until IS NULL "
                    + "OR sign_in_window_until <= ? THEN 1 ELSE sign_in_failures + 1 END, sign_in_window_until = CASE "
                    + "WHEN sign_in_window_until IS NULL OR sign_in_window_until <= ? THEN ? ELSE sign_in_window_until END "
                    + "WHERE id = ?", nowMillis, nowMillis, nowMillis + windowMillis, userId);
            final Long count = Sql.first(connection, "SELECT sign_in_failures FROM users WHERE id = ?",
                    row -> row.longValue("sign_in_failures"), userId);
            return count == null ? 0L : count;
        });
    }

    @Override
    public boolean isSignInLocked(final String userId, final long nowMillis) {
        final Long until = database.read(connection -> Sql.first(connection,
                "SELECT sign_in_locked_until FROM users WHERE id = ?", row -> row.longValue("sign_in_locked_until"), userId));
        return until != null && until > nowMillis;
    }

    @Override
    public void lockSignIn(final String userId, final long untilMillis) {
        database.write(connection -> Sql.update(connection, "UPDATE users SET sign_in_locked_until = ? WHERE id = ?",
                untilMillis, userId));
    }

    @Override
    public void resetSignInAttempts(final String userId) {
        database.write(connection -> Sql.update(connection,
                "UPDATE users SET sign_in_failures = 0, sign_in_window_until = NULL WHERE id = ?", userId));
    }

}
