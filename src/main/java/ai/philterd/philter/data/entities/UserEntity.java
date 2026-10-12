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

import java.util.Date;

public class UserEntity extends AbstractEncryptedEntity {

    private String id;
    /** When the user was created, from storage; not written back. */
    private Date createdAt;
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

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** When the user was created, or {@code null} for a user not yet stored. */
    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Date createdAt) {
        this.createdAt = createdAt;
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
