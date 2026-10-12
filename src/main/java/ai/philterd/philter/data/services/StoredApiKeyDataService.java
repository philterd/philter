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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.SessionKeyConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The rules for API keys, the same in every storage implementation: how keys are generated and stored only as
 * their hashes, session keys' idle windows and lifetimes, that a change applies only to the caller's own active
 * key, that revoked keys are kept but never authenticate, what is audited, and when the key cache forgets a
 * key. An implementation stores the keys and makes their conditional changes atomic.
 */
public abstract class StoredApiKeyDataService implements ApiKeyDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyDataService.class);
    private static final String ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int API_KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    protected final AuditEventPublisher auditEventPublisher;
    private final ApiKeyCache apiKeyCache;

    protected StoredApiKeyDataService(final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache) {
        this.auditEventPublisher = auditEventPublisher;
        this.apiKeyCache = apiKeyCache;
    }

    /** Stores a new key and returns its id. */
    protected abstract String insert(final ApiKeyEntity key);

    /** Whether any key, active or ended, has this hash. */
    protected abstract boolean existsByHash(final String apiKeyHash);

    /** The active key with this hash, or null. */
    protected abstract ApiKeyEntity findActiveByHash(final String apiKeyHash);

    /** Whether an ended session key has this hash. */
    protected abstract boolean isEndedSessionByHash(final String apiKeyHash);

    /** Sets the scopes of the owner's key, only while it is active, and returns whether it did. */
    protected abstract boolean setScopes(final String id, final String userId, final Set<String> scopes);

    /**
     * Ends the given keys of the owner that are still active: marks them deleted at {@code when} and removes the
     * sign-in's client address and user agent, which are kept only while a session lasts.
     */
    protected abstract void end(final List<String> ids, final String userId, final Date when);

    /**
     * Records a use of a live session key: sets its last use to {@code now} and its idle window to
     * {@code idleExpiresAt}, only while it is active and neither its idle window nor its lifetime has passed.
     */
    protected abstract boolean touch(final String id, final Date now, final Date idleExpiresAt);

    /** Active session keys, the one with this id when it is not null, whose idle window or lifetime has passed. */
    protected abstract List<ApiKeyEntity> findDueSessionKeys(final String sessionKeyId, final Date now);

    /**
     * Ends the key, only if it is still an active session key past its idle window or lifetime, and returns
     * whether it did. Of instances ending the same key at once, exactly one does.
     */
    protected abstract boolean endIfDue(final String id, final Date now);

    /** The user's active session keys, except the one with {@code keepKeyId}. */
    protected abstract List<ApiKeyEntity> findActiveSessionKeys(final String userId, final String keepKeyId);

    private String generateApiKey() {

        LOGGER.info("Generating new API key.");

        String apiKey;
        final StringBuilder sb = new StringBuilder(API_KEY_LENGTH);

        do {
            sb.setLength(0);
            for (int i = 0; i < API_KEY_LENGTH; i++) {
                sb.append(ALPHANUMERIC_CHARS.charAt(SECURE_RANDOM.nextInt(ALPHANUMERIC_CHARS.length())));
            }
            apiKey = "sk_" + sb;
        } while (existsByHash(EncryptionService.hashSha256(apiKey)));

        return apiKey;

    }

    private static ApiKeyEntity newKey(final String userId, final String apiKey, final Set<String> scopes, final Date now) {
        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setApiKey(apiKey);
        apiKeyEntity.setApiKeyHash(EncryptionService.hashSha256(apiKey));
        apiKeyEntity.setApiKeyPrefix(apiKey.substring(0, 12) + "...");
        apiKeyEntity.setDeleted(false);
        apiKeyEntity.setTimestamp(now);
        apiKeyEntity.setScopes(scopes);
        return apiKeyEntity;
    }

    @Override
    public ServiceResponse createApiKey(final String requestId, final String userId, final String source) {
        return createApiKey(requestId, userId, source, ApiKeyScope.all());
    }

    @Override
    public ServiceResponse createApiKey(final String requestId, final String userId, final String source,
                                        final Set<String> scopes) {
        return createApiKey(requestId, userId, source, scopes, null);
    }

    @Override
    public ServiceResponse createApiKey(final String requestId, final String userId, final String source,
                                        final Set<String> scopes, final String auditDetails) {

        final String apiKey = generateApiKey();
        final String apiKeyId = insert(newKey(userId, apiKey, scopes, new Date()));

        // The key is the subject and the user it belongs to is the object: an event naming only the
        // key leaves a reader unable to say whose access was just created.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, userId, source, auditDetails);

        return new ServiceResponse(apiKey, true, 200);

    }

    @Override
    public boolean ensureApiKey(final String requestId, final String userId, final String apiKey, final String source) {

        if (existsByHash(EncryptionService.hashSha256(apiKey))) {
            return false;
        }

        // Every scope: the bootstrap key provisions a deployment before anyone has decided what it
        // should be limited to, and a key with no scopes could call nothing at all.
        final ApiKeyEntity apiKeyEntity = newKey(userId, apiKey, ApiKeyScope.all(), new Date());
        apiKeyEntity.setBootstrap(true);

        final String apiKeyId = insert(apiKeyEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, source);

        return true;

    }

    @Override
    public boolean isEndedSessionKey(final String apiKey) {
        return isEndedSessionByHash(EncryptionService.hashSha256(apiKey));
    }

    @Override
    public ApiKeyEntity findOneByApiKey(final String apiKey) {
        return findActiveByHash(EncryptionService.hashSha256(apiKey));
    }

    @Override
    public List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session) {
        return findAllBySession(userId, offset, limit, session, false);
    }

    @Override
    public int count(final String userId) {
        return count(userId, false);
    }

    /**
     * Confirms that the given key exists, is active, and belongs to {@code callerUserId}, returning the
     * stored key or {@code null}.
     *
     * <p>The check is made against the key as stored, not against the entity the caller passed in: an
     * object handed to this service carries whatever owner the caller put on it, so trusting its
     * {@code userId} would make the check meaningless.
     */
    private ApiKeyEntity requireOwnedKey(final String callerUserId, final ApiKeyEntity apiKeyEntity,
                                         final String operation) {

        if (callerUserId == null || apiKeyEntity == null || apiKeyEntity.getId() == null) {
            LOGGER.warn("Refusing to {} an API key: the key or the caller is not identified.", operation);
            return null;
        }

        final ApiKeyEntity stored = findOneById(apiKeyEntity.getId());

        if (stored == null || !callerUserId.equals(stored.getUserId())) {
            LOGGER.warn("Refusing to {} API key {}: it does not belong to the calling user.",
                    operation, apiKeyEntity.getId());
            return null;
        }

        return stored;

    }

    @Override
    public ServiceResponse updateScopes(final String requestId, final String callerUserId,
                                        final ApiKeyEntity apiKeyEntity,
                                        final Set<String> scopes, final String source) {
        return updateScopes(requestId, callerUserId, apiKeyEntity, scopes, source, null);
    }

    @Override
    public ServiceResponse updateScopes(final String requestId, final String callerUserId,
                                        final ApiKeyEntity apiKeyEntity,
                                        final Set<String> scopes, final String source,
                                        final String auditDetails) {

        final ApiKeyEntity stored = requireOwnedKey(callerUserId, apiKeyEntity, "change the scopes of");

        if (stored == null) {
            return new ServiceResponse("API key not found.", false, 404);
        }

        // Capture what the key held before the change: an audit entry saying only that the scopes
        // changed does not tell an auditor whether the key was widened or narrowed.
        final Set<String> previousScopes = new LinkedHashSet<>(stored.getScopes());

        stored.setScopes(scopes);
        if (!setScopes(stored.getId(), callerUserId, scopes)) {
            return new ServiceResponse("API key not found.", false, 404);
        }

        // Keep the caller's copy consistent with what was stored.
        apiKeyEntity.setScopes(stored.getScopes());

        // Evict so the new scopes apply to the next request rather than after the cache TTL.
        apiKeyCache.delete(stored.getApiKeyHash());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_SCOPES_CHANGED, stored.getId(),
                stored.getId(), source,
                "from: [" + String.join(", ", previousScopes) + "], to: [" + String.join(", ", stored.getScopes()) + "]"
                        + (auditDetails == null ? "" : ", " + auditDetails));

        return ServiceResponse.success();

    }

    @Override
    public ServiceResponse deleteByApiKey(final String requestId, final String callerUserId,
                                          final ApiKeyEntity apiKeyEntity, final String source) {
        return deleteByApiKey(requestId, callerUserId, apiKeyEntity, source, null);
    }

    @Override
    public ServiceResponse deleteByApiKey(final String requestId, final String callerUserId,
                                          final ApiKeyEntity apiKeyEntity, final String source,
                                          final String auditDetails) {

        final ApiKeyEntity owned = requireOwnedKey(callerUserId, apiKeyEntity, "delete");

        if (owned == null) {
            return new ServiceResponse("API key not found.", false, 404);
        }

        // API keys are never removed - just marked as deleted (revoked) with the time of deletion. This
        // preserves usage history so audit entries referencing the key id still resolve to it. A deleted
        // key can never authenticate again and cannot be reactivated.
        final Date deletedAt = new Date();
        end(List.of(owned.getId()), callerUserId, deletedAt);

        // Keep the caller's copy consistent with what was stored.
        apiKeyEntity.setDeleted(true);
        apiKeyEntity.setDeletedAt(deletedAt);

        // Evict the key from the cache so the deletion takes effect immediately.
        apiKeyCache.delete(owned.getApiKeyHash());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, owned.getId(), owned.getId(), source, auditDetails);

        return ServiceResponse.success();

    }

    @Override
    public ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
                                         final String source, final String auditDetails) {
        return createSessionKey(requestId, userId, scopes, false, false, source, auditDetails);
    }

    @Override
    public ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
                                         final boolean passwordChangeOnly, final boolean mfaEnrollmentOnly,
                                         final String source, final String auditDetails) {
        return createSessionKey(requestId, userId, scopes, passwordChangeOnly, mfaEnrollmentOnly, source, auditDetails,
                null, null);
    }

    @Override
    public ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
                                         final boolean passwordChangeOnly, final boolean mfaEnrollmentOnly,
                                         final String source, final String auditDetails, final String clientAddress,
                                         final String userAgent) {

        final String apiKey = generateApiKey();
        final Date now = new Date();
        final int idleSeconds = SessionKeyConfig.idleTimeoutMinutes() * 60;
        final Date expiresAt = new Date(now.getTime() + SessionKeyConfig.maxLifetimeMinutes() * 60_000L);

        final ApiKeyEntity apiKeyEntity = newKey(userId, apiKey, scopes, now);
        apiKeyEntity.setSession(true);
        apiKeyEntity.setExpiresAt(expiresAt);
        apiKeyEntity.setIdleTimeoutSeconds(idleSeconds);
        apiKeyEntity.setIdleExpiresAt(idleExpiry(now, idleSeconds, expiresAt));
        apiKeyEntity.setLastUsedAt(now);
        apiKeyEntity.setPasswordChangeOnly(passwordChangeOnly);
        apiKeyEntity.setMfaEnrollmentOnly(mfaEnrollmentOnly);
        apiKeyEntity.setClientAddress(clientAddress);
        apiKeyEntity.setUserAgent(userAgent);
        apiKeyEntity.setId(insert(apiKeyEntity));

        String session = "session: true";
        if (passwordChangeOnly) {
            session += ", password change only";
        }
        if (mfaEnrollmentOnly) {
            session += ", MFA enrollment only";
        }
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyEntity.getId(), userId,
                source, auditDetails == null ? session : session + ", " + auditDetails);

        return apiKeyEntity;

    }

    @Override
    public boolean touchSessionKey(final ApiKeyEntity sessionKey) {
        final Date now = new Date();
        return touch(sessionKey.getId(), now, idleExpiry(now, sessionKey.getIdleTimeoutSeconds(), sessionKey.getExpiresAt()));
    }

    @Override
    public long expireSessionKeys(final String sessionKeyId) {

        final Date now = new Date();

        long expired = 0;
        for (final ApiKeyEntity key : findDueSessionKeys(sessionKeyId, now)) {

            // Ending repeats the expiry condition: a request on another instance may have moved the idle
            // window forward since the read, and that key is not expired.
            if (endIfDue(key.getId(), now)) {
                apiKeyCache.delete(key.getApiKeyHash());
                final boolean lifetime = key.getExpiresAt() != null && !key.getExpiresAt().after(now);
                auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.API_KEY_EXPIRED, key.getId(),
                        key.getUserId(), Source.SYSTEM.getSource(),
                        lifetime ? "reason: maximum lifetime reached" : "reason: idle timeout");
                expired++;
            }

        }

        return expired;

    }

    /** The end of the idle window starting at {@code from}, never later than the key's lifetime. */
    private static Date idleExpiry(final Date from, final int idleSeconds, final Date expiresAt) {
        final Date idle = new Date(from.getTime() + idleSeconds * 1000L);
        return expiresAt != null && expiresAt.before(idle) ? expiresAt : idle;
    }

    @Override
    public long revokeSessionKeys(final String requestId, final String userId, final String source,
                                  final String auditDetails) {
        return revokeSessionKeys(requestId, userId, source, auditDetails, null);
    }

    @Override
    public long revokeSessionKeys(final String requestId, final String userId, final String source,
                                  final String auditDetails, final String keepKeyId) {

        final List<ApiKeyEntity> affected = findActiveSessionKeys(userId, keepKeyId);

        if (affected.isEmpty()) {
            return 0;
        }

        // By id, so a key created after the read is not revoked without being evicted and audited.
        final List<String> ids = new ArrayList<>();
        for (final ApiKeyEntity key : affected) {
            ids.add(key.getId());
        }
        end(ids, userId, new Date());

        for (final ApiKeyEntity key : affected) {
            apiKeyCache.delete(key.getApiKeyHash());
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, key.getId(), key.getId(), source, auditDetails);
        }

        return affected.size();

    }

}
