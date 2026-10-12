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

import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.model.ServiceResponse;

import java.util.List;
import java.util.Set;

/**
 * API keys, both long-lived keys and the session keys issued at sign-in. Only a hash of each key is stored.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A key is found by its hash only while it is active: a revoked key, or a session key past its idle
 *   window or lifetime, never authenticates again, even though its record is kept.</li>
 *   <li>{@link #expireSessionKeys} ends each due session key exactly once, even when several instances sweep
 *   at the same time.</li>
 *   <li>Changes to a key apply only to a key that is still active and owned by the caller; otherwise nothing is
 *   written.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping keys, and order by creation
 *   time.</li>
 * </ul>
 */
public interface ApiKeyDataService {

    /** Environment variable holding an API key to seed at startup. See {@link #ensureApiKey}. */
    String BOOTSTRAP_API_KEY_ENV = "PHILTER_BOOTSTRAP_API_KEY";

    /**
     * Creates a key carrying every scope. Retained for callers that provision a fully-privileged
     * credential, such as the bootstrap key.
     */
    ServiceResponse createApiKey(final String requestId, final String userId, final String source);

    /**
     * Creates a key limited to the given scopes. A key with no scopes can call nothing, which is the
     * safe reading of "no permissions were granted".
     */
    ServiceResponse createApiKey(final String requestId, final String userId, final String source,
            final Set<String> scopes);

    /**
     * Creates a key limited to the given scopes, appending {@code auditDetails} to the
     * {@code api_key_created} event. Pass the acting principal there when the key is minted for a
     * user other than the one asking for it, so the audit log says who did it and not only that a
     * key appeared.
     */
    ServiceResponse createApiKey(final String requestId, final String userId, final String source,
            final Set<String> scopes, final String auditDetails);

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
    boolean ensureApiKey(final String requestId, final String userId, final String apiKey, final String source);

    /**
     * Returns the user's active (non-deleted) bootstrap key, or {@code null} if none. Shows
     * whether a key seeded from {@link #BOOTSTRAP_API_KEY_ENV} is still in use.
     */
    ApiKeyEntity findActiveBootstrapKey(final String userId);

    /**
     * Looks up an active key by its id. Used to re-read a key from the database before acting on it, so
     * an authorization check is made against stored state rather than against a caller-supplied object.
     */
    ApiKeyEntity findOneById(final String id);

    /**
     * Whether the key is a session key that has ended: signed out, revoked, or expired. Lets a refusal
     * tell a person to sign in again rather than calling their key unknown. Only session keys are
     * reported, so a revoked long-lived key reads like any unknown key.
     */
    boolean isEndedSessionKey(final String apiKey);

    ApiKeyEntity findOneByApiKey(final String apiKey);

    /** Lists a page of a user's active (non-deleted) API keys. */
    List<ApiKeyEntity> findAll(final String userId, final int offset, final int limit);

    /**
     * Lists a page of a user's active API keys: only session keys when {@code session} is true, only
     * long-lived keys when false, and both when null.
     */
    List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session);

    /** As above, newest first when {@code descending}. */
    List<ApiKeyEntity> findAllBySession(final String userId, final int offset, final int limit, final Boolean session,
            final boolean descending);

    /** Counts what {@link #findAllBySession(String, int, int, Boolean)} lists. */
    int countBySession(final String userId, final Boolean session);

    /**
     * Lists a page of a user's API keys sorted by creation time. When {@code includeDeleted} is false,
     * soft-deleted keys are excluded; when true, deleted keys are included, marked as deleted (a deleted key is revoked and can never authenticate again).
     */
    List<ApiKeyEntity> findAll(final String userId, final int offset, final int limit, final boolean includeDeleted);

    /** Counts a user's active (non-deleted) API keys. */
    int count(final String userId);

    /** Counts a user's API keys; when {@code includeDeleted} is false, soft-deleted keys are excluded. */
    int count(final String userId, final boolean includeDeleted);

    /**
     * Replaces the scopes on an existing key. The key itself is unchanged, so integrations keep working
     * with the same credential and only what it may call changes.
     */
    ServiceResponse updateScopes(final String requestId, final String callerUserId,
            final ApiKeyEntity apiKeyEntity,
            final Set<String> scopes, final String source);

    /** As above, appending {@code auditDetails} (such as the acting principal) to the audit event. */
    ServiceResponse updateScopes(final String requestId, final String callerUserId,
            final ApiKeyEntity apiKeyEntity,
            final Set<String> scopes, final String source,
            final String auditDetails);

    ServiceResponse deleteByApiKey(final String requestId, final String callerUserId,
            final ApiKeyEntity apiKeyEntity, final String source);

    /** As above, recording {@code auditDetails} (such as the acting principal) on the audit event. */
    ServiceResponse deleteByApiKey(final String requestId, final String callerUserId,
            final ApiKeyEntity apiKeyEntity, final String source,
            final String auditDetails);

    long deleteAllByUserId(final String requestId, final String userId, final String source);

    /**
     * Issues a session key: an API key for a person who signed in, which expires after
     * {@code SESSION_KEY_IDLE_TIMEOUT_MINUTES} without a request or {@code SESSION_KEY_MAX_LIFETIME_MINUTES}
     * after issue, whichever comes first. Audited as {@code api_key_created} with {@code auditDetails}.
     *
     * @return the stored key, carrying the plaintext in {@link ApiKeyEntity#getApiKey()}, which is shown once.
     */
    ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
            final String source, final String auditDetails);

    /**
     * As above, for a user who must do something before anything else: with {@code passwordChangeOnly}
     * the key can change its user's password, with {@code mfaEnrollmentOnly} it can enroll in MFA, and
     * either way it can sign out, and nothing more.
     */
    ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
            final boolean passwordChangeOnly, final boolean mfaEnrollmentOnly,
            final String source, final String auditDetails);

    /**
     * As above, recording the address and user agent of the client that signed in, so a person can tell
     * their sessions apart. Both are removed when the session ends.
     */
    ApiKeyEntity createSessionKey(final String requestId, final String userId, final Set<String> scopes,
            final boolean passwordChangeOnly, final boolean mfaEnrollmentOnly,
            final String source, final String auditDetails, final String clientAddress,
            final String userAgent);

    /**
     * Records a request made with a session key and reports whether the key is still valid. One
     * conditional update, against the database rather than a cache, so every node agrees: it matches
     * only a live session key whose idle window and lifetime have not passed, and moves the idle window
     * forward from now.
     */
    boolean touchSessionKey(final ApiKeyEntity sessionKey);

    /**
     * Marks session keys whose idle window or lifetime has passed as deleted, evicts them, and
     * audits each as {@code api_key_expired}. Each key is claimed with a conditional update, so when
     * several nodes sweep at once, each expiry is recorded once.
     *
     * @param sessionKeyId one key to check, or {@code null} for every session key.
     * @return how many keys were expired.
     */
    long expireSessionKeys(final String sessionKeyId);

    /**
     * Revokes the user's active session keys, the keys issued at sign-in, leaving long-lived keys alone.
     * Each is evicted from the cache and audited as {@code api_key_deleted} with {@code auditDetails}.
     *
     * @return how many keys were revoked.
     */
    long revokeSessionKeys(final String requestId, final String userId, final String source,
            final String auditDetails);

    /** As above, leaving {@code keepKeyId}, when given, alone: signing out every session but the current one. */
    long revokeSessionKeys(final String requestId, final String userId, final String source,
            final String auditDetails, final String keepKeyId);

    void update(final ApiKeyEntity key);

}
