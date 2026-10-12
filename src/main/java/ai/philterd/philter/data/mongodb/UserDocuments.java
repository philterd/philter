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

import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;

/** Maps {@link UserEntity} to and from the documents MongoDB stores. */
public final class UserDocuments {

    private UserDocuments() {
    }

    public static UserEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final UserEntity userEntity = new UserEntity();
        userEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        // The ObjectId carries the creation time; users have no separate created field.
        userEntity.setCreatedAt(document.getObjectId("_id") == null ? null : document.getObjectId("_id").getDate());
        // The login identifier is the username. Accounts created before the username field existed
        // stored the login id under "email", so fall back to that when "username" is absent.
        userEntity.setUsername(document.getString("username") != null
                ? document.getString("username") : document.getString("email"));
        userEntity.setEmail(document.getString("email"));
        userEntity.setRole(document.getString("role"));
        userEntity.setFpeKey(readEncrypted(document, encryptionService, "fpe_key"));
        userEntity.setWebhookUrl(document.getString("webhook_url"));
        userEntity.setWebhookSecret(readEncrypted(document, encryptionService, "webhook_secret"));
        userEntity.setPassword(document.getString("password"));
        userEntity.setPasswordChangeRequired(document.getBoolean("password_change_required", false));
        userEntity.setMfaEnabled(document.getBoolean("mfa_enabled", false));
        userEntity.setMfaSecret(readEncrypted(document, encryptionService, "mfa_secret"));
        userEntity.setMfaPendingSecret(readEncrypted(document, encryptionService, "mfa_pending_secret"));
        userEntity.setMfaFailedAttempts(document.getInteger("mfa_failed_attempts", 0));
        userEntity.setMfaLocked(document.getBoolean("mfa_locked", false));
        final Number lastStep = document.get("mfa_last_used_time_step", Number.class);
        userEntity.setMfaLastUsedTimeStep(lastStep == null ? 0L : lastStep.longValue());
        userEntity.setDeactivated(document.getBoolean("deactivated", false));
        userEntity.setDeactivatedAt(document.getDate("deactivated_at"));
        return userEntity;
    }

    public static Document toDocument(final UserEntity entity, final EncryptionService encryptionService) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("username", entity.getUsername());
        document.put("email", entity.getEmail());
        document.put("role", entity.getRole());
        // Per-user secrets are encrypted at rest (ciphertext plus a <field>_key); the webhook URL is an
        // endpoint, not a credential, and the entity.getPassword() is a bcrypt hash, stored as-is.
        putEncrypted(document, encryptionService, entity.getId(), "fpe_key", entity.getFpeKey());
        document.put("webhook_url", entity.getWebhookUrl());
        putEncrypted(document, encryptionService, entity.getId(), "webhook_secret", entity.getWebhookSecret());
        document.put("password", entity.getPassword());
        document.put("password_change_required", entity.isPasswordChangeRequired());
        document.put("mfa_enabled", entity.isMfaEnabled());
        putEncrypted(document, encryptionService, entity.getId(), "mfa_secret", entity.getMfaSecret());
        putEncrypted(document, encryptionService, entity.getId(), "mfa_pending_secret", entity.getMfaPendingSecret());
        document.put("mfa_failed_attempts", entity.getMfaFailedAttempts());
        document.put("mfa_locked", entity.isMfaLocked());
        document.put("mfa_last_used_time_step", entity.getMfaLastUsedTimeStep());
        document.put("deactivated", entity.isDeactivated());
        document.put("deactivated_at", entity.getDeactivatedAt());
        return document;
    }

    /**
     * Reads a field encrypted by {@link #putEncrypted}. Returns null when absent or empty. A value
     * present without its {@code _key} is legacy plaintext (written before the field was encrypted) and
     * is returned as-is; it is re-written encrypted on the next save.
     */
    private static String readEncrypted(final Document document, final EncryptionService encryptionService, final String field) {
        final String value = document.getString(field);
        if (value == null || value.isEmpty()) {
            return null;
        }
        final String key = document.getString(field + "_key");
        if (key != null && !key.isEmpty()) {
            return encryptionService.decrypt(value, key);
        }
        return value;
    }

    /**
     * Encrypts a sensitive field at rest, storing the ciphertext under {@code field} and its data key
     * under {@code field + "_key"}. A null or empty value is stored as empty (and read back as null).
     */
    private static void putEncrypted(final Document document, final EncryptionService encryptionService, final String id,
                                     final String field, final String value) {
        if (value == null || value.isEmpty()) {
            document.put(field, "");
            document.put(field + "_key", "");
        } else {
            final EncryptResult result = encryptionService.encrypt(value, id != null ? id : "");
            document.put(field, result.getEncryptedText());
            document.put(field + "_key", result.getEncryptionKey());
        }
    }

}
