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

import ai.philterd.philter.model.ApiKeyScope;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

public class ApiKeyEntity extends AbstractEntity {

    private String id;
    private String apiKeyHash;
    private String apiKeyPrefix;
    // API keys are soft-deleted: a deleted key is revoked (it can never authenticate again) but the
    // row is retained, marked deleted with the time of deletion, so audit entries that reference the
    // key id still resolve to it. Unlike a deactivated user, a deleted key cannot be reactivated.
    private boolean deleted;
    private Date deletedAt;
    private Date timestamp;
    private transient String apiKey;
    private String userId;
    // True for a key seeded at startup from PHILTER_BOOTSTRAP_API_KEY, so a client can flag that the
    // bootstrap key is still in use and should be replaced with a narrower one.
    private boolean bootstrap;
    // True for a key issued when a person signs in, as opposed to a long-lived key. Changing or resetting
    // the user's password revokes these.
    private boolean session;
    // Session keys only: the end of the maximum lifetime, the idle timeout, the end of the current idle
    // window (moved forward by each request), and the last request. All null or zero on long-lived keys.
    private Date expiresAt;
    private int idleTimeoutSeconds;
    private Date idleExpiresAt;
    private Date lastUsedAt;
    // A session key issued to a user who must change their password: it can only do that, or sign out.
    private boolean passwordChangeOnly;
    // A session key issued to a user who must enroll in MFA: it can only enroll, or sign out.
    private boolean mfaEnrollmentOnly;

    /**
     * For a session key, the address of the client that signed in, kept only while the session lasts.
     * Transient so it is left out of the API key cache, which may be a shared Valkey/Redis server: nothing
     * reads it from there, and it is personal data. The database mapping writes it explicitly.
     */
    private transient String clientAddress;

    /** For a session key, the user agent that signed in, kept only while the session lasts. Transient as above. */
    private transient String userAgent;

    /** The scopes this key carries. Empty means the key can call nothing. */
    private Set<String> scopes = new LinkedHashSet<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeletedAt(final Date deletedAt) {
        this.deletedAt = deletedAt;
    }

    public Date getDeletedAt() {
        return deletedAt;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public void setApiKeyHash(final String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public void setApiKeyPrefix(final String apiKeyPrefix) {
        this.apiKeyPrefix = apiKeyPrefix;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public boolean isBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(final boolean bootstrap) {
        this.bootstrap = bootstrap;
    }

    public boolean isSession() {
        return session;
    }

    public void setSession(final boolean session) {
        this.session = session;
    }

    public Date getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(final Date expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getIdleTimeoutSeconds() {
        return idleTimeoutSeconds;
    }

    public void setIdleTimeoutSeconds(final int idleTimeoutSeconds) {
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public Date getIdleExpiresAt() {
        return idleExpiresAt;
    }

    public void setIdleExpiresAt(final Date idleExpiresAt) {
        this.idleExpiresAt = idleExpiresAt;
    }

    public Date getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(final Date lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public boolean isPasswordChangeOnly() {
        return passwordChangeOnly;
    }

    public void setPasswordChangeOnly(final boolean passwordChangeOnly) {
        this.passwordChangeOnly = passwordChangeOnly;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(final String clientAddress) {
        this.clientAddress = clientAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(final String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isMfaEnrollmentOnly() {
        return mfaEnrollmentOnly;
    }

    public void setMfaEnrollmentOnly(final boolean mfaEnrollmentOnly) {
        this.mfaEnrollmentOnly = mfaEnrollmentOnly;
    }

    /** Whether this key is limited to finishing what must happen before the user can do anything else. */
    public boolean isRestricted() {
        return passwordChangeOnly || mfaEnrollmentOnly;
    }

    public Set<String> getScopes() {
        return scopes;
    }

    public void setScopes(final Set<String> scopes) {
        this.scopes = scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes);
    }

    /** Whether this key carries the given scope. */
    public boolean hasScope(final ApiKeyScope scope) {
        // Null-safe on both sides: a key deserialized from the cache with no scopes field must deny,
        // not throw. An exception here would surface as a 500 rather than a clean refusal.
        return scope != null && scopes != null && scopes.contains(scope.getScope());
    }

}
