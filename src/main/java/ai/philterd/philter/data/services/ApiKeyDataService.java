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
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;

public class ApiKeyDataService extends AbstractService<ApiKeyEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyDataService.class);
    private static final String ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int API_KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Environment variable holding an API key to seed at startup. See {@link #ensureApiKey}. */
    public static final String BOOTSTRAP_API_KEY_ENV = "PHILTER_BOOTSTRAP_API_KEY";

    private final ApiKeyCache apiKeyCache;

    public ApiKeyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache) {
        super(mongoClient, "api_keys", auditEventPublisher);
        this.apiKeyCache = apiKeyCache;

        // Authentication looks keys up by hash; listing is scoped to a user and sorted by timestamp.
        ensureIndex(Indexes.ascending("api_key_hash", "deleted"));
        ensureIndex(Indexes.ascending("user_id", "deleted", "timestamp"));
        // The expiry sweep looks for live session keys.
        ensureIndex(Indexes.ascending("session", "deleted"));
    }

    private String generateApiKey() {

        LOGGER.info("Generating new API key.");

        String apiKey;
        final StringBuilder sb = new StringBuilder(API_KEY_LENGTH);

        do {
            sb.setLength(0); // Reset StringBuilder for retry
            for (int i = 0; i < API_KEY_LENGTH; i++) {
                final int index = SECURE_RANDOM.nextInt(ALPHANUMERIC_CHARS.length());
                sb.append(ALPHANUMERIC_CHARS.charAt(index));
            }
            apiKey = "sk_" + sb.toString();
        } while (doesApiKeyExist(apiKey));

        return apiKey;

    }

    private boolean doesApiKeyExist(final String apiKey) {

        final String apiKeyHash = EncryptionService.hashSha256(apiKey);
        final Document query = new Document("api_key_hash", apiKeyHash);

        final Document document = collection.find(query).first();

        return document != null;

    }

    /**
     * Creates a key carrying every scope. Retained for callers that provision a fully-privileged
     * credential, such as the bootstrap key.
     */
    public ServiceResponse createApiKey(final String requestId, final ObjectId userId, final String source) {
        return createApiKey(requestId, userId, source, ApiKeyScope.all());
    }

    /**
     * Creates a key limited to the given scopes. A key with no scopes can call nothing, which is the
     * safe reading of "no permissions were granted".
     */
    public ServiceResponse createApiKey(final String requestId, final ObjectId userId, final String source,
                                        final Set<String> scopes) {
        return createApiKey(requestId, userId, source, scopes, null);
    }

    /**
     * Creates a key limited to the given scopes, appending {@code auditDetails} to the
     * {@code api_key_created} event. Pass the acting principal there when the key is minted for a
     * user other than the one asking for it, so the audit log says who did it and not only that a
     * key appeared.
     */
    public ServiceResponse createApiKey(final String requestId, final ObjectId userId, final String source,
                                        final Set<String> scopes, final String auditDetails) {

        // Generate an API key.
        final String apiKey = generateApiKey();

        // API keys should always be at least 6 characters, but validate to be safe
        if(apiKey.length() < 6) {
            throw new IllegalStateException("Generated API key is too short: " + apiKey.length() + " characters");
        }

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setApiKey(apiKey);
        apiKeyEntity.setApiKeyHash(EncryptionService.hashSha256(apiKey));
        apiKeyEntity.setApiKeyPrefix(apiKey.substring(0, 12) + "...");
        apiKeyEntity.setDeleted(false);
        apiKeyEntity.setTimestamp(new Date());
        apiKeyEntity.setScopes(scopes);
        final ObjectId apiKeyId = save(apiKeyEntity);

        // The key is the subject and the user it belongs to is the object: an event naming only the
        // key leaves a reader unable to say whose access was just created.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, userId, source, auditDetails);

        return new ServiceResponse(apiKey, true, 200);

    }

    /**
     * Idempotently persists a caller-supplied API key for the given user. Used to bootstrap a
     * known key at startup (the {@code PHILTER_BOOTSTRAP_API_KEY} environment variable) as the
     * admin user's first credential.
     *
     * <p>No-op if a key with the same value already exists (including a previously deleted one,
     * so a revoked bootstrap key is not resurrected on restart). The caller is responsible for
     * validating the key format.
     *
     * @return {@code true} if a new key was created, {@code false} if it already existed
     */
    public boolean ensureApiKey(final String requestId, final ObjectId userId, final String apiKey, final String source) {

        if (doesApiKeyExist(apiKey)) {
            return false;
        }

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setApiKey(apiKey);
        apiKeyEntity.setApiKeyHash(EncryptionService.hashSha256(apiKey));
        apiKeyEntity.setApiKeyPrefix(apiKey.substring(0, 12) + "...");
        apiKeyEntity.setDeleted(false);
        apiKeyEntity.setTimestamp(new Date());
        apiKeyEntity.setBootstrap(true);

        // Every scope: the bootstrap key provisions a deployment before anyone has decided what it
        // should be limited to, and a key with no scopes could call nothing at all.
        apiKeyEntity.setScopes(ApiKeyScope.all());

        final ObjectId apiKeyId = save(apiKeyEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, source);

        return true;

    }

    /**
     * Returns the user's active (non-deleted) bootstrap key, or {@code null} if none. Shows
     * whether a key seeded from {@link #BOOTSTRAP_API_KEY_ENV} is still in use.
     */
    public ApiKeyEntity findActiveBootstrapKey(final ObjectId userId) {

        final Document query = new Document("user_id", userId)
                .append("bootstrap", true)
                .append("deleted", false);

        final Document document = collection.find(query).first();

        return document != null ? ApiKeyEntity.fromDocument(document) : null;

    }

    /**
     * Looks up an active key by its id. Used to re-read a key from the database before acting on it, so
     * an authorization check is made against stored state rather than against a caller-supplied object.
     */
    public ApiKeyEntity findOneById(final ObjectId id) {

        if (id == null) {
            return null;
        }

        final Document document = collection.find(new Document("_id", id).append("deleted", false)).first();

        return document == null ? null : ApiKeyEntity.fromDocument(document);

    }

    public ApiKeyEntity findOneByApiKey(final String apiKey) {

        final String apiKeyHash = EncryptionService.hashSha256(apiKey);
        final Document query = new Document("api_key_hash", apiKeyHash).append("deleted", false);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ApiKeyEntity.fromDocument(document);
        } else {
            return null;
        }

    }

    /** Lists a page of a user's active (non-deleted) API keys. */
    public List<ApiKeyEntity> findAll(final ObjectId userId, final int offset, final int limit) {
        return findAll(userId, offset, limit, false);
    }

    /**
     * Lists a page of a user's active API keys: only session keys when {@code session} is true, only
     * long-lived keys when false, and both when null.
     */
    public List<ApiKeyEntity> findAllBySession(final ObjectId userId, final int offset, final int limit, final Boolean session) {
        return findAllBySession(userId, offset, limit, session, false);
    }

    /** As above, newest first when {@code descending}. */
    public List<ApiKeyEntity> findAllBySession(final ObjectId userId, final int offset, final int limit, final Boolean session,
                                               final boolean descending) {
        final List<ApiKeyEntity> keys = new ArrayList<>();
        for (final Document document : collection.find(activeKeys(userId, session))
                .sort(new Listings.Sort("timestamp", descending).toBson()).skip(offset).limit(limit)) {
            keys.add(ApiKeyEntity.fromDocument(document));
        }
        return keys;
    }

    /** Counts what {@link #findAllBySession(ObjectId, int, int, Boolean)} lists. */
    public int countBySession(final ObjectId userId, final Boolean session) {
        return (int) collection.countDocuments(activeKeys(userId, session));
    }

    private static Document activeKeys(final ObjectId userId, final Boolean session) {
        final Document query = new Document("deleted", false).append("user_id", userId);
        if (session != null) {
            // Keys made before session keys existed have no session field, and are long-lived.
            query.append("session", session ? true : new Document("$ne", true));
        }
        return query;
    }

    /**
     * Lists a page of a user's API keys sorted by creation time. When {@code includeDeleted} is false,
     * soft-deleted keys are excluded; when true, deleted keys are included, marked as deleted (a deleted key is revoked and can never authenticate again).
     */
    public List<ApiKeyEntity> findAll(final ObjectId userId, final int offset, final int limit, final boolean includeDeleted) {

        final Document query = new Document();
        if (!includeDeleted) {
            query.append("deleted", false);
        }
        if (userId != null) {
            query.append("user_id", userId);
        }

        final FindIterable<Document> documents = collection.find(query).sort(Sorts.ascending("timestamp")).skip(offset).limit(limit);

        final List<ApiKeyEntity> apiKeyEntities = new ArrayList<>();

        for(final Document document : documents) {
            apiKeyEntities.add(ApiKeyEntity.fromDocument(document));
        }

        return apiKeyEntities;

    }

    /** Counts a user's active (non-deleted) API keys. */
    public int count(final ObjectId userId) {
        return count(userId, false);
    }

    /** Counts a user's API keys; when {@code includeDeleted} is false, soft-deleted keys are excluded. */
    public int count(final ObjectId userId, final boolean includeDeleted) {

        final Document query = new Document();
        if (!includeDeleted) {
            query.append("deleted", false);
        }
        if (userId != null) {
            query.append("user_id", userId);
        }

        return (int) collection.countDocuments(query);

    }

    /**
     * Confirms that the given key exists, is active, and belongs to {@code callerUserId}, returning the
     * stored key or {@code null}.
     *
     * <p>The check is made against the key as stored, not against the entity the caller passed in: an
     * object handed to this service carries whatever owner the caller put on it, so trusting its
     * {@code userId} would make the check meaningless. Callers today only ever pass a key they listed
     * for the signed-in user, so a failure here means a bug or an attempt to act on someone else's key,
     * and is logged as such.
     */
    private ApiKeyEntity requireOwnedKey(final ObjectId callerUserId, final ApiKeyEntity apiKeyEntity,
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

    /**
     * Replaces the scopes on an existing key. The key itself is unchanged, so integrations keep working
     * with the same credential and only what it may call changes.
     */
    public ServiceResponse updateScopes(final String requestId, final ObjectId callerUserId,
                                        final ApiKeyEntity apiKeyEntity,
                                        final Set<String> scopes, final String source) {
        return updateScopes(requestId, callerUserId, apiKeyEntity, scopes, source, null);
    }

    /** As above, appending {@code auditDetails} (such as the acting principal) to the audit event. */
    public ServiceResponse updateScopes(final String requestId, final ObjectId callerUserId,
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
        final var result = collection.updateOne(Filters.and(Filters.eq("_id", stored.getId()),
                Filters.eq("user_id", callerUserId), Filters.ne("deleted", true)),
                new Document("$set", new Document("scopes", new java.util.ArrayList<>(scopes))));
        if (result.getMatchedCount() != 1) return new ServiceResponse("API key not found.", false, 404);

        // Keep the caller's copy consistent with what was stored, so a caller holding the old object
        // sees the change without re-reading.
        apiKeyEntity.setScopes(stored.getScopes());

        // Evict so the new scopes apply to the next request rather than after the cache TTL, matching
        // how revocation is handled. The cached entity carries the old scopes until it is dropped.
        apiKeyCache.delete(stored.getApiKeyHash());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_SCOPES_CHANGED, stored.getId(),
                stored.getId(), source,
                "from: [" + String.join(", ", previousScopes) + "], to: [" + String.join(", ", stored.getScopes()) + "]"
                        + (auditDetails == null ? "" : ", " + auditDetails));

        return ServiceResponse.success();

    }

    public ServiceResponse deleteByApiKey(final String requestId, final ObjectId callerUserId,
                                          final ApiKeyEntity apiKeyEntity, final String source) {
        return deleteByApiKey(requestId, callerUserId, apiKeyEntity, source, null);
    }

    /** As above, recording {@code auditDetails} (such as the acting principal) on the audit event. */
    public ServiceResponse deleteByApiKey(final String requestId, final ObjectId callerUserId,
                                          final ApiKeyEntity apiKeyEntity, final String source,
                                          final String auditDetails) {

        final ApiKeyEntity owned = requireOwnedKey(callerUserId, apiKeyEntity, "delete");

        if (owned == null) {
            return new ServiceResponse("API key not found.", false, 404);
        }

        // API keys are never removed from the database - just marked as deleted (revoked) with the time
        // of deletion. This preserves usage history so audit entries referencing the key id still
        // resolve to it. A deleted key can never authenticate again and cannot be reactivated.
        // Write the key as stored, not the object the caller passed: ownership was checked against the
        // stored key, so persisting caller-supplied fields would let a tampered copy through the guard.
        owned.setDeleted(true);
        owned.setDeletedAt(new Date());
        collection.updateOne(Filters.and(Filters.eq("_id", owned.getId()), Filters.eq("user_id", callerUserId)),
                new Document("$set", new Document("deleted", true).append("deleted_at", owned.getDeletedAt())));

        // Keep the caller's copy consistent with what was stored, so a caller holding the old object
        // sees the deletion without re-reading.
        apiKeyEntity.setDeleted(true);
        apiKeyEntity.setDeletedAt(owned.getDeletedAt());

        // Evict the key from the cache so the deletion takes effect immediately rather than after the
        // cache TTL. The cache is keyed by the key's hash, which the entity carries.
        apiKeyCache.delete(owned.getApiKeyHash());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, owned.getId(), owned.getId(), source, auditDetails);

        return ServiceResponse.success();

    }

    public long deleteAllByUserId(final String requestId, final ObjectId userId, final String source) {

        final Document query = new Document("user_id", userId).append("deleted", false);

        // Capture the affected keys for the audit trail before the bulk update.
        final List<ApiKeyEntity> affected = new ArrayList<>();
        for (final Document document : collection.find(query)) {
            affected.add(ApiKeyEntity.fromDocument(document));
        }

        if (affected.isEmpty()) {
            return 0;
        }

        // Mark them all deleted (revoked) in a single bulk update rather than one update per key,
        // stamping the same deletion time on each.
        collection.updateMany(query, new Document("$set", new Document("deleted", true).append("deleted_at", new Date())));

        // Evict each from the cache and preserve the per-key audit events (one API_KEY_DELETED per key).
        for (final ApiKeyEntity apiKeyEntity : affected) {
            apiKeyCache.delete(apiKeyEntity.getApiKeyHash());
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, apiKeyEntity.getId(), apiKeyEntity.getId(), source);
        }

        return affected.size();

    }

    /**
     * Issues a session key: an API key for a person who signed in, which expires after
     * {@code SESSION_KEY_IDLE_TIMEOUT_MINUTES} without a request or {@code SESSION_KEY_MAX_LIFETIME_MINUTES}
     * after issue, whichever comes first. Audited as {@code api_key_created} with {@code auditDetails}.
     *
     * @return the stored key, carrying the plaintext in {@link ApiKeyEntity#getApiKey()}, which is shown once.
     */
    public ApiKeyEntity createSessionKey(final String requestId, final ObjectId userId, final Set<String> scopes,
                                         final String source, final String auditDetails) {
        return createSessionKey(requestId, userId, scopes, false, false, source, auditDetails);
    }

    /**
     * As above, for a user who must do something before anything else: with {@code passwordChangeOnly}
     * the key can change its user's password, with {@code mfaEnrollmentOnly} it can enroll in MFA, and
     * either way it can sign out, and nothing more.
     */
    public ApiKeyEntity createSessionKey(final String requestId, final ObjectId userId, final Set<String> scopes,
                                         final boolean passwordChangeOnly, final boolean mfaEnrollmentOnly,
                                         final String source, final String auditDetails) {

        final String apiKey = generateApiKey();
        final Date now = new Date();
        final int idleSeconds = SessionKeyConfig.idleTimeoutMinutes() * 60;
        final Date expiresAt = new Date(now.getTime() + SessionKeyConfig.maxLifetimeMinutes() * 60_000L);

        final ApiKeyEntity apiKeyEntity = new ApiKeyEntity();
        apiKeyEntity.setUserId(userId);
        apiKeyEntity.setApiKey(apiKey);
        apiKeyEntity.setApiKeyHash(EncryptionService.hashSha256(apiKey));
        apiKeyEntity.setApiKeyPrefix(apiKey.substring(0, 12) + "...");
        apiKeyEntity.setDeleted(false);
        apiKeyEntity.setTimestamp(now);
        apiKeyEntity.setScopes(scopes);
        apiKeyEntity.setSession(true);
        apiKeyEntity.setExpiresAt(expiresAt);
        apiKeyEntity.setIdleTimeoutSeconds(idleSeconds);
        apiKeyEntity.setIdleExpiresAt(idleExpiry(now, idleSeconds, expiresAt));
        apiKeyEntity.setLastUsedAt(now);
        apiKeyEntity.setPasswordChangeOnly(passwordChangeOnly);
        apiKeyEntity.setMfaEnrollmentOnly(mfaEnrollmentOnly);
        apiKeyEntity.setId(save(apiKeyEntity));

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

    /**
     * Records a request made with a session key and reports whether the key is still valid. One
     * conditional update, against the database rather than a cache, so every node agrees: it matches
     * only a live session key whose idle window and lifetime have not passed, and moves the idle window
     * forward from now.
     */
    public boolean touchSessionKey(final ApiKeyEntity sessionKey) {

        final Date now = new Date();

        return collection.updateOne(
                Filters.and(Filters.eq("_id", sessionKey.getId()), Filters.eq("session", true),
                        Filters.eq("deleted", false), Filters.gt("expires_at", now), Filters.gt("idle_expires_at", now)),
                new Document("$set", new Document("last_used_at", now)
                        .append("idle_expires_at", idleExpiry(now, sessionKey.getIdleTimeoutSeconds(), sessionKey.getExpiresAt()))))
                .getMatchedCount() == 1;

    }

    /**
     * Marks session keys whose idle window or lifetime has passed as deleted, evicts them, and
     * audits each as {@code api_key_expired}. Each key is claimed with a conditional update, so when
     * several nodes sweep at once, each expiry is recorded once.
     *
     * @param sessionKeyId one key to check, or {@code null} for every session key.
     * @return how many keys were expired.
     */
    public long expireSessionKeys(final ObjectId sessionKeyId) {

        final Date now = new Date();
        final org.bson.conversions.Bson due = Filters.and(Filters.eq("session", true), Filters.eq("deleted", false),
                Filters.or(Filters.lte("expires_at", now), Filters.lte("idle_expires_at", now)));

        long expired = 0;
        for (final Document document : collection.find(sessionKeyId == null ? due : Filters.and(Filters.eq("_id", sessionKeyId), due))) {

            final ApiKeyEntity key = ApiKeyEntity.fromDocument(document);
            // The claim repeats the expiry condition: a request on another node may have moved the idle
            // window forward since the read, and that key is not expired.
            final boolean claimed = collection.updateOne(Filters.and(Filters.eq("_id", key.getId()), due),
                    new Document("$set", new Document("deleted", true).append("deleted_at", now))).getMatchedCount() == 1;

            if (claimed) {
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

    /**
     * Revokes the user's active session keys, the keys issued at sign-in, leaving long-lived keys alone.
     * Each is evicted from the cache and audited as {@code api_key_deleted} with {@code auditDetails}.
     *
     * @return how many keys were revoked.
     */
    public long revokeSessionKeys(final String requestId, final ObjectId userId, final String source,
                                  final String auditDetails) {

        final List<ApiKeyEntity> affected = new ArrayList<>();
        for (final Document document : collection.find(Filters.and(Filters.eq("user_id", userId),
                Filters.eq("session", true), Filters.eq("deleted", false)))) {
            affected.add(ApiKeyEntity.fromDocument(document));
        }

        if (affected.isEmpty()) {
            return 0;
        }

        // By id, so a key created after the read is not revoked without being evicted and audited.
        final List<ObjectId> ids = new ArrayList<>();
        for (final ApiKeyEntity key : affected) {
            ids.add(key.getId());
        }
        collection.updateMany(Filters.and(Filters.in("_id", ids), Filters.eq("deleted", false)),
                new Document("$set", new Document("deleted", true).append("deleted_at", new Date())));

        for (final ApiKeyEntity key : affected) {
            apiKeyCache.delete(key.getApiKeyHash());
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, key.getId(), key.getId(), source, auditDetails);
        }

        return affected.size();

    }

    @Override
    public void update(final ApiKeyEntity key) {
        throw new UnsupportedOperationException("Use scope or revocation operations.");
    }
}
