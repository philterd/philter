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

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.SessionKeyConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** MongoDB implementation of {@link ApiKeyDataService}. */
public class MongoApiKeyDataService extends AbstractService<ApiKeyEntity> implements ApiKeyDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiKeyDataService.class);
    private static final String ALPHANUMERIC_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int API_KEY_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyCache apiKeyCache;

    public MongoApiKeyDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache) {
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
        final String apiKeyId = save(apiKeyEntity);

        // The key is the subject and the user it belongs to is the object: an event naming only the
        // key leaves a reader unable to say whose access was just created.
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, userId, source, auditDetails);

        return new ServiceResponse(apiKey, true, 200);

    }

    @Override
    public boolean ensureApiKey(final String requestId, final String userId, final String apiKey, final String source) {

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

        final String apiKeyId = save(apiKeyEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, source);

        return true;

    }

    @Override
    public ApiKeyEntity findActiveBootstrapKey(final String userId) {

        final Document query = new Document("user_id", MongoIds.objectId(userId))
                .append("bootstrap", true)
                .append("deleted", false);

        final Document document = collection.find(query).first();

        return document != null ? ApiKeyDocuments.fromDocument(document) : null;

    }

    @Override
    public ApiKeyEntity findOneById(final String id) {

        if (id == null) {
            return null;
        }

        final Document document = collection.find(new Document("_id", MongoIds.objectId(id)).append("deleted", false)).first();

        return document == null ? null : ApiKeyDocuments.fromDocument(document);

    }

    @Override
    public boolean isEndedSessionKey(final String apiKey) {
        return collection.find(new Document("api_key_hash", EncryptionService.hashSha256(apiKey))
                .append("session", true).append("deleted", true)).first() != null;
    }

    @Override
    public ApiKeyEntity findOneByApiKey(final String apiKey) {

        final String apiKeyHash = EncryptionService.hashSha256(apiKey);
        final Document query = new Document("api_key_hash", apiKeyHash).append("deleted", false);

        final Document document = collection.find(query).first();

        if(document != null) {
            return ApiKeyDocuments.fromDocument(document);
        } else {
            return null;
        }

    }

    @Override
    public List<ApiKeyEntity> findAll(final String userId, final int offset, final int limit) {
        return findAll(userId, offset, limit, false);
    }

    @Override
    public List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session) {
        return findAllBySession(userId, offset, limit, session, false);
    }

    @Override
    public List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session,
                                               final boolean descending) {
        final List<ApiKeyEntity> keys = new ArrayList<>();
        for (final Document document : collection.find(activeKeys(userId, session))
                .sort(MongoListings.sort(new Listings.Sort("timestamp", descending))).skip(offset).limit(limit)) {
            keys.add(ApiKeyDocuments.fromDocument(document));
        }
        return keys;
    }

    @Override
    public int countBySession(final String userId, final Boolean session) {
        return (int) collection.countDocuments(activeKeys(userId, session));
    }

    private static Document activeKeys(final String userId, final Boolean session) {
        final Document query = new Document("deleted", false).append("user_id", MongoIds.objectId(userId));
        if (session != null) {
            // Keys made before session keys existed have no session field, and are long-lived.
            query.append("session", session ? true : new Document("$ne", true));
        }
        return query;
    }

    @Override
    public List<ApiKeyEntity> findAll(final String userId, final int offset, final int limit, final boolean includeDeleted) {

        final Document query = new Document();
        if (!includeDeleted) {
            query.append("deleted", false);
        }
        if (userId != null) {
            query.append("user_id", MongoIds.objectId(userId));
        }

        final FindIterable<Document> documents = collection.find(query).sort(Sorts.ascending("timestamp")).skip(offset).limit(limit);

        final List<ApiKeyEntity> apiKeyEntities = new ArrayList<>();

        for(final Document document : documents) {
            apiKeyEntities.add(ApiKeyDocuments.fromDocument(document));
        }

        return apiKeyEntities;

    }

    @Override
    public int count(final String userId) {
        return count(userId, false);
    }

    @Override
    public int count(final String userId, final boolean includeDeleted) {

        final Document query = new Document();
        if (!includeDeleted) {
            query.append("deleted", false);
        }
        if (userId != null) {
            query.append("user_id", MongoIds.objectId(userId));
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
        final var result = collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(stored.getId())),
                Filters.eq("user_id", MongoIds.objectId(callerUserId)), Filters.ne("deleted", true)),
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

        // API keys are never removed from the database - just marked as deleted (revoked) with the time
        // of deletion. This preserves usage history so audit entries referencing the key id still
        // resolve to it. A deleted key can never authenticate again and cannot be reactivated.
        // Write the key as stored, not the object the caller passed: ownership was checked against the
        // stored key, so persisting caller-supplied fields would let a tampered copy through the guard.
        owned.setDeleted(true);
        owned.setDeletedAt(new Date());
        collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(owned.getId())), Filters.eq("user_id", MongoIds.objectId(callerUserId))),
                ended(owned.getDeletedAt()));

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

    @Override
    public long deleteAllByUserId(final String requestId, final String userId, final String source) {

        final Document query = new Document("user_id", MongoIds.objectId(userId)).append("deleted", false);

        // Capture the affected keys for the audit trail before the bulk update.
        final List<ApiKeyEntity> affected = new ArrayList<>();
        for (final Document document : collection.find(query)) {
            affected.add(ApiKeyDocuments.fromDocument(document));
        }

        if (affected.isEmpty()) {
            return 0;
        }

        // Mark them all deleted (revoked) in a single bulk update rather than one update per key,
        // stamping the same deletion time on each.
        collection.updateMany(query, ended(new Date()));

        // Evict each from the cache and preserve the per-key audit events (one API_KEY_DELETED per key).
        for (final ApiKeyEntity apiKeyEntity : affected) {
            apiKeyCache.delete(apiKeyEntity.getApiKeyHash());
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, apiKeyEntity.getId(), apiKeyEntity.getId(), source);
        }

        return affected.size();

    }

    @Override
    public ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
                                         final String source, final String auditDetails) {
        return createSessionKey(requestId, userId, scopes, false, false, source, auditDetails);
    }

    /**
     * The update that ends a key: marks it deleted at {@code when} and removes the sign-in's client
     * address and user agent, which are kept only while a session lasts. Deleted keys are kept, so
     * leaving them would keep every past sign-in's address and browser for good.
     */
    private static Document ended(final Date when) {
        return new Document("$set", new Document("deleted", true).append("deleted_at", when))
                .append("$unset", new Document("client_address", "").append("user_agent", ""));
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
        apiKeyEntity.setClientAddress(clientAddress);
        apiKeyEntity.setUserAgent(userAgent);
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

    @Override
    public boolean touchSessionKey(final ApiKeyEntity sessionKey) {

        final Date now = new Date();

        return collection.updateOne(
                Filters.and(Filters.eq("_id", MongoIds.objectId(sessionKey.getId())), Filters.eq("session", true),
                        Filters.eq("deleted", false), Filters.gt("expires_at", now), Filters.gt("idle_expires_at", now)),
                new Document("$set", new Document("last_used_at", now)
                        .append("idle_expires_at", idleExpiry(now, sessionKey.getIdleTimeoutSeconds(), sessionKey.getExpiresAt()))))
                .getMatchedCount() == 1;

    }

    @Override
    public long expireSessionKeys(final String sessionKeyId) {

        final Date now = new Date();
        final org.bson.conversions.Bson due = Filters.and(Filters.eq("session", true), Filters.eq("deleted", false),
                Filters.or(Filters.lte("expires_at", now), Filters.lte("idle_expires_at", now)));

        long expired = 0;
        for (final Document document : collection.find(sessionKeyId == null ? due : Filters.and(Filters.eq("_id", MongoIds.objectId(sessionKeyId)), due))) {

            final ApiKeyEntity key = ApiKeyDocuments.fromDocument(document);
            // The claim repeats the expiry condition: a request on another node may have moved the idle
            // window forward since the read, and that key is not expired.
            final boolean claimed = collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(key.getId())), due),
                    ended(now)).getMatchedCount() == 1;

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

    @Override
    public long revokeSessionKeys(final String requestId, final String userId, final String source,
                                  final String auditDetails) {
        return revokeSessionKeys(requestId, userId, source, auditDetails, null);
    }

    @Override
    public long revokeSessionKeys(final String requestId, final String userId, final String source,
                                  final String auditDetails, final String keepKeyId) {

        final List<ApiKeyEntity> affected = new ArrayList<>();
        for (final Document document : collection.find(Filters.and(Filters.eq("user_id", MongoIds.objectId(userId)),
                Filters.eq("session", true), Filters.eq("deleted", false),
                keepKeyId == null ? new Document() : Filters.ne("_id", MongoIds.objectId(keepKeyId))))) {
            affected.add(ApiKeyDocuments.fromDocument(document));
        }

        if (affected.isEmpty()) {
            return 0;
        }

        // By id, so a key created after the read is not revoked without being evicted and audited.
        final List<String> ids = new ArrayList<>();
        for (final ApiKeyEntity key : affected) {
            ids.add(key.getId());
        }
        collection.updateMany(Filters.and(Filters.in("_id", MongoIds.objectIds(ids)), Filters.eq("deleted", false)),
                ended(new Date()));

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
    @Override
    protected Document toDocument(final ApiKeyEntity entity) {
        return ApiKeyDocuments.toDocument(entity);
    }

}
