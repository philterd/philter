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
package ai.philterd.philter.data.entities;

import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;

public class UserEntity extends AbstractEncryptedEntity {

    private ObjectId id;
    private String username;
    private String email;
    private String role;
    private String fpeKey;
    private String webhookUrl;
    private String webhookSecret;
    // A bcrypt hash, or null for a user that only authenticates with long-lived API keys.
    private String password;
    // Set when an administrator chose the password, so the user must replace it at next sign-in.
    private boolean passwordChangeRequired;
    // TOTP multi-factor authentication. The secret is encrypted at rest. A pending secret is one an
    // enrollment started but has not confirmed, so it does not yet apply at sign-in.
    private boolean mfaEnabled;
    private String mfaSecret;
    private String mfaPendingSecret;
    // Consecutive bad codes; at the limit the user is locked until an administrator unlocks them.
    private int mfaFailedAttempts;
    private boolean mfaLocked;
    // The last time step a code was accepted for, so no code is accepted twice.
    private long mfaLastUsedTimeStep;
    // Users are deactivated rather than deleted: the record and all of the user's data are retained
    // (so the account can be reactivated and so audit and ledger entries that reference the user id
    // still resolve to a name), but a deactivated user's API keys are refused.
    private boolean deactivated;
    private Date deactivatedAt;

    public static UserEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final UserEntity userEntity = new UserEntity();
        userEntity.setId(document.getObjectId("_id"));
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

    @Override
    public Document toDocument(final EncryptionService encryptionService) {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("username", username);
        document.put("email", email);
        document.put("role", role);
        // Per-user secrets are encrypted at rest (ciphertext plus a <field>_key); the webhook URL is an
        // endpoint, not a credential, and the password is a bcrypt hash, stored as-is.
        putEncrypted(document, encryptionService, "fpe_key", fpeKey);
        document.put("webhook_url", webhookUrl);
        putEncrypted(document, encryptionService, "webhook_secret", webhookSecret);
        document.put("password", password);
        document.put("password_change_required", passwordChangeRequired);
        document.put("mfa_enabled", mfaEnabled);
        putEncrypted(document, encryptionService, "mfa_secret", mfaSecret);
        putEncrypted(document, encryptionService, "mfa_pending_secret", mfaPendingSecret);
        document.put("mfa_failed_attempts", mfaFailedAttempts);
        document.put("mfa_locked", mfaLocked);
        document.put("mfa_last_used_time_step", mfaLastUsedTimeStep);
        document.put("deactivated", deactivated);
        document.put("deactivated_at", deactivatedAt);
        return document;
    }

    /**
     * Encrypts a sensitive field at rest, storing the ciphertext under {@code field} and its data key
     * under {@code field + "_key"}. A null or empty value is stored as empty (and read back as null).
     */
    private void putEncrypted(final Document document, final EncryptionService encryptionService, final String field, final String value) {
        if (value == null || value.isEmpty()) {
            document.put(field, "");
            document.put(field + "_key", "");
        } else {
            final EncryptResult result = encryptionService.encrypt(value, id != null ? id.toHexString() : "");
            document.put(field, result.getEncryptedText());
            document.put(field + "_key", result.getEncryptionKey());
        }
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getFpeKey() {
        return fpeKey;
    }

    public void setFpeKey(String fpeKey) {
        this.fpeKey = fpeKey;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(final String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public String getWebhookSecret() {
        return webhookSecret;
    }

    public void setWebhookSecret(final String webhookSecret) {
        this.webhookSecret = webhookSecret;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(final String password) {
        this.password = password;
    }

    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    public void setPasswordChangeRequired(final boolean passwordChangeRequired) {
        this.passwordChangeRequired = passwordChangeRequired;
    }

    public boolean isMfaEnabled() {
        return mfaEnabled;
    }

    public void setMfaEnabled(final boolean mfaEnabled) {
        this.mfaEnabled = mfaEnabled;
    }

    public String getMfaSecret() {
        return mfaSecret;
    }

    public void setMfaSecret(final String mfaSecret) {
        this.mfaSecret = mfaSecret;
    }

    public String getMfaPendingSecret() {
        return mfaPendingSecret;
    }

    public void setMfaPendingSecret(final String mfaPendingSecret) {
        this.mfaPendingSecret = mfaPendingSecret;
    }

    public int getMfaFailedAttempts() {
        return mfaFailedAttempts;
    }

    public void setMfaFailedAttempts(final int mfaFailedAttempts) {
        this.mfaFailedAttempts = mfaFailedAttempts;
    }

    public boolean isMfaLocked() {
        return mfaLocked;
    }

    public void setMfaLocked(final boolean mfaLocked) {
        this.mfaLocked = mfaLocked;
    }

    public long getMfaLastUsedTimeStep() {
        return mfaLastUsedTimeStep;
    }

    public void setMfaLastUsedTimeStep(final long mfaLastUsedTimeStep) {
        this.mfaLastUsedTimeStep = mfaLastUsedTimeStep;
    }

    public boolean isDeactivated() {
        return deactivated;
    }

    public void setDeactivated(final boolean deactivated) {
        this.deactivated = deactivated;
    }

    public Date getDeactivatedAt() {
        return deactivatedAt;
    }

    public void setDeactivatedAt(final Date deactivatedAt) {
        this.deactivatedAt = deactivatedAt;
    }

}
