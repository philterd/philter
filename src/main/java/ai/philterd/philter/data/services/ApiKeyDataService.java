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
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Indexes;
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
     * safe reading of "no permissions were granted"; the dashboard requires at least one.
     */
    public ServiceResponse createApiKey(final String requestId, final ObjectId userId, final String source,
                                        final Set<String> scopes) {

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

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_CREATED, apiKeyId, source);

        return new ServiceResponse(apiKey, true, 200);

    }

    /**
     * Idempotently persists a caller-supplied API key for the given user. Used to bootstrap a
     * known key at startup (the {@code PHILTER_BOOTSTRAP_API_KEY} environment variable) so that
     * automation and turnkey deployments have a credential without the interactive UI flow.
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
     * Returns the user's active (non-deleted) bootstrap key, or {@code null} if none. Used by the UI
     * to flag that a key seeded from {@link #BOOTSTRAP_API_KEY_ENV} is still in use.
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
     * Lists a page of a user's API keys sorted by creation time. When {@code includeDeleted} is false,
     * soft-deleted keys are excluded; when true, deleted keys are included so the account view can show
     * them clearly marked (a deleted key is revoked and can never authenticate again).
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

        final ApiKeyEntity stored = requireOwnedKey(callerUserId, apiKeyEntity, "change the scopes of");

        if (stored == null) {
            return new ServiceResponse("API key not found.", false, 404);
        }

        // Capture what the key held before the change: an audit entry saying only that the scopes
        // changed does not tell an auditor whether the key was widened or narrowed.
        final Set<String> previousScopes = new LinkedHashSet<>(stored.getScopes());

        stored.setScopes(scopes);
        update(stored);

        // Keep the caller's copy consistent with what was stored, so a UI holding the old object shows
        // the change without re-reading.
        apiKeyEntity.setScopes(stored.getScopes());

        // Evict so the new scopes apply to the next request rather than after the cache TTL, matching
        // how revocation is handled. The cached entity carries the old scopes until it is dropped.
        apiKeyCache.delete(stored.getApiKeyHash());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_SCOPES_CHANGED, stored.getId(),
                stored.getId(), source,
                "from: [" + String.join(", ", previousScopes) + "], to: [" + String.join(", ", stored.getScopes()) + "]");

        return ServiceResponse.success();

    }

    public ServiceResponse deleteByApiKey(final String requestId, final ObjectId callerUserId,
                                          final ApiKeyEntity apiKeyEntity, final String source) {

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
        update(owned);

        // Keep the caller's copy consistent with what was stored, so a UI holding the old object
        // reflects the deletion without re-reading.
        apiKeyEntity.setDeleted(true);
        apiKeyEntity.setDeletedAt(owned.getDeletedAt());

        // Evict the key from the cache so the deletion takes effect immediately rather than after the
        // cache TTL. The cache is keyed by the key's hash, which the entity carries.
        apiKeyCache.delete(owned.getApiKeyHash());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.API_KEY_DELETED, owned.getId(), owned.getId(), source);

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

}
