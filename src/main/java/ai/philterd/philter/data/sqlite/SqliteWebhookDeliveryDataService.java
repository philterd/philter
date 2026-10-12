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

import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredWebhookDeliveryDataService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;

/**
 * Stores webhook deliveries in SQLite, removing finished ones after their retention period, both as deliveries are
 * claimed and on {@link SqliteRetention}'s schedule.
 */
public class SqliteWebhookDeliveryDataService extends StoredWebhookDeliveryDataService implements SqliteRetention.Expiring {

    private static final String LIVE_CLAIM = "id = ? AND status = '" + WebhookDeliveryEntity.STATUS_PROCESSING
            + "' AND claim_token = ? AND claim_expires_at > ?";

    private final SqliteDatabase database;
    private final EncryptionService encryptionService;

    public SqliteWebhookDeliveryDataService(final SqliteDatabase database, final EncryptionService encryptionService) {
        this.database = database;
        this.encryptionService = encryptionService;
    }

    private WebhookDeliveryEntity read(final Sql.Row row, final boolean withSecrets) throws SQLException {
        final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setId(row.string("id"));
        delivery.setUserId(row.string("user_id"));
        delivery.setDocumentId(row.string("document_id"));
        delivery.setEventType(row.string("event_type"));
        delivery.setStatus(row.string("status"));
        delivery.setUrl(row.string("url"));
        if (withSecrets) {
            delivery.setSecret(SecretFields.open(encryptionService, row.string("secret"), row.string("secret_encrypted_key")));
            delivery.setPayload(row.string("payload"));
            delivery.setClaimToken(row.string("claim_token"));
        }
        delivery.setAttempts(row.intValue("attempts"));
        delivery.setClaimExpiresAt(row.date("claim_expires_at"));
        delivery.setLastError(row.string("last_error"));
        delivery.setNextAttemptAt(row.date("next_attempt_at"));
        delivery.setCreatedAt(row.date("created_at"));
        delivery.setUpdatedAt(row.date("updated_at"));
        delivery.setDeliveredAt(row.date("delivered_at"));
        delivery.setCompletedAt(row.date("completed_at"));
        return delivery;
    }

    @Override
    public void removeExpired() {
        database.write(connection -> prune(connection, new Date()));
    }

    private static int prune(final Connection connection, final Date now) throws SQLException {
        return Sql.update(connection, "DELETE FROM webhook_deliveries WHERE completed_at <= ?",
                new Date(now.getTime() - retentionSeconds() * 1000L));
    }

    @Override
    protected WebhookDeliveryEntity claim(final Date now, final String claimToken, final Date claimExpiresAt) {
        return database.write(connection -> {
            prune(connection, now);
            // A worker that repeatedly dies must not leave a delivery in an endless reclaim loop.
            Sql.update(connection, "UPDATE webhook_deliveries SET status = ?, last_error = ?, updated_at = ?, completed_at = ?, "
                    + "next_attempt_at = NULL, claim_token = NULL, claim_expires_at = NULL "
                    + "WHERE status = ? AND claim_expires_at <= ? AND attempts >= ?",
                    WebhookDeliveryEntity.STATUS_FAILED, "Delivery claim expired after the final attempt.", now, now,
                    WebhookDeliveryEntity.STATUS_PROCESSING, now, MAX_ATTEMPTS);
            final String id = Sql.first(connection, "SELECT id FROM webhook_deliveries WHERE attempts < ? AND "
                    + "((status = ? AND next_attempt_at <= ?) OR (status = ? AND claim_expires_at <= ?)) "
                    + "ORDER BY next_attempt_at ASC, seq ASC LIMIT 1", row -> row.string("id"),
                    MAX_ATTEMPTS, WebhookDeliveryEntity.STATUS_PENDING, now, WebhookDeliveryEntity.STATUS_PROCESSING, now);
            if (id == null) {
                return null;
            }
            Sql.update(connection, "UPDATE webhook_deliveries SET status = ?, claim_token = ?, claim_expires_at = ?, "
                    + "updated_at = ?, attempts = attempts + 1 WHERE id = ?",
                    WebhookDeliveryEntity.STATUS_PROCESSING, claimToken, claimExpiresAt, now, id);
            return Sql.first(connection, "SELECT * FROM webhook_deliveries WHERE id = ?", row -> read(row, true), id);
        });
    }

    @Override
    protected boolean markDelivered(final String id, final String claimToken, final Date now) {
        return database.write(connection -> Sql.update(connection, "UPDATE webhook_deliveries SET status = ?, delivered_at = ?, "
                + "completed_at = ?, updated_at = ?, next_attempt_at = NULL, claim_token = NULL, claim_expires_at = NULL WHERE "
                + LIVE_CLAIM, WebhookDeliveryEntity.STATUS_DELIVERED, now, now, now, id, claimToken, now)) == 1;
    }

    @Override
    protected boolean endAttempt(final String id, final String claimToken, final int currentAttempts,
                                 final Date nextAttemptAt, final String errorMessage, final Date now) {
        final boolean exhausted = nextAttemptAt == null;
        return database.write(connection -> Sql.update(connection, "UPDATE webhook_deliveries SET status = ?, last_error = ?, "
                + "updated_at = ?, next_attempt_at = ?, completed_at = ?, claim_token = NULL, claim_expires_at = NULL WHERE "
                + LIVE_CLAIM + " AND attempts = ?",
                exhausted ? WebhookDeliveryEntity.STATUS_FAILED : WebhookDeliveryEntity.STATUS_PENDING, errorMessage, now,
                nextAttemptAt, exhausted ? now : null, id, claimToken, now, currentAttempts)) == 1;
    }

    @Override
    protected void insert(final WebhookDeliveryEntity delivery) {
        final SecretFields.Sealed secret = SecretFields.seal(encryptionService, delivery.getSecret(), delivery.getUserId());
        database.write(connection -> Sql.update(connection, "INSERT INTO webhook_deliveries (id, user_id, document_id, "
                + "event_type, status, url, secret, secret_encrypted_key, payload, attempts, claim_token, claim_expires_at, "
                + "last_error, next_attempt_at, created_at, updated_at, delivered_at, completed_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                delivery.getId(), delivery.getUserId(), delivery.getDocumentId(), delivery.getEventType(), delivery.getStatus(),
                delivery.getUrl(), secret == null ? null : secret.ciphertext(), secret == null ? null : secret.key(),
                delivery.getPayload(), delivery.getAttempts(), delivery.getClaimToken(), delivery.getClaimExpiresAt(),
                delivery.getLastError(), delivery.getNextAttemptAt(), delivery.getCreatedAt(), delivery.getUpdatedAt(),
                delivery.getDeliveredAt(), delivery.getCompletedAt()));
    }

    @Override
    protected boolean exists(final String id) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM webhook_deliveries WHERE id = ?", id) > 0);
    }

    @Override
    public List<WebhookDeliveryEntity> findByUserId(final String userId, final int offset, final int limit, final boolean descending) {
        final String direction = descending ? " DESC" : " ASC";
        return database.read(connection -> Sql.query(connection, "SELECT * FROM webhook_deliveries WHERE user_id = ? "
                + "ORDER BY created_at" + direction + ", seq" + direction + " LIMIT ? OFFSET ?",
                row -> read(row, false), userId, limit, offset));
    }

    @Override
    public long countByUserId(final String userId) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM webhook_deliveries WHERE user_id = ?", userId));
    }

}
