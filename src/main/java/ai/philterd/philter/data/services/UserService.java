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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.policies.DefaultPolicy;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.client.model.Updates;

public class UserService extends AbstractEncryptedService<UserEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    private final PasswordEncoder passwordEncoder;

    public UserService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "users", encryptionService, auditEventPublisher);
        this.passwordEncoder = new BCryptPasswordEncoder();

        ensureIndex(Indexes.ascending("username"), new com.mongodb.client.model.IndexOptions().unique(true));

    }

    /**
     * Looks up an <strong>active</strong> (not deactivated) user by username. Deactivated users are
     * excluded so they cannot sign in and cannot be targeted via the cross-user {@code owner}
     * parameter. Use {@link #findOneById(ObjectId)} or {@link #findUsernamesByIds(Collection)} to resolve
     * a deactivated user for audit and ledger display, and {@link #findAnyByUsername(String)} to detect a
     * username that is already taken (including by a deactivated account).
     */
    public UserEntity findByUsername(final String username) {
        final Document document = collection.find(
                Filters.and(Filters.eq("username", username), Filters.ne("deactivated", true))).first();
        if (document != null) {
            return UserEntity.fromDocument(document, encryptionService);
        }
        return null;
    }

    /**
     * Looks up a user by username regardless of deactivation state. Used when creating an account to
     * reject a username that already belongs to any user, active or deactivated: a deactivated account
     * keeps its username reserved so it can be reactivated rather than duplicated.
     */
    public UserEntity findAnyByUsername(final String username) {
        final Document document = collection.find(Filters.eq("username", username)).first();
        if (document != null) {
            return UserEntity.fromDocument(document, encryptionService);
        }
        return null;
    }

    /**
     * Returns whether the user with the given id is deactivated, fetching only the deactivation flag.
     * Used on the API authentication hot path to reject keys whose owning user has been deactivated,
     * so deactivation and reactivation take effect immediately without touching the user's API keys.
     * A missing user is treated as deactivated (no active access).
     */
    public boolean isDeactivated(final ObjectId userId) {
        final Document document = collection.find(Filters.eq("_id", userId))
                .projection(Projections.include("deactivated")).first();
        return document == null || document.getBoolean("deactivated", false);
    }

    /**
     * Looks up a user by id, including deactivated users, so that audit and ledger entries that
     * reference a deactivated user still resolve to the retained record.
     */
    public UserEntity findOneById(final ObjectId id) {

        final Document query = new Document("_id", id);

        final Document document = collection.find(query).first();

        if(document != null) {

            return UserEntity.fromDocument(document, encryptionService);

        } else {

            return null;

        }

    }

    /**
     * Resolves several user ids to their usernames in a single query, returning a map of id to
     * username. Used by the admin "All ..." views to label each row with its owner without issuing one
     * lookup per row. Ids with no matching user are simply absent from the returned map.
     */
    public Map<ObjectId, String> findUsernamesByIds(final Collection<ObjectId> ids) {

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<ObjectId, String> usernamesById = new HashMap<>();
        for (final Document document : collection.find(Filters.in("_id", ids))) {
            final UserEntity user = UserEntity.fromDocument(document, encryptionService);
            usernamesById.put(user.getId(), user.getUsername());
        }

        return usernamesById;

    }

    // Backward-compatible overloads without the optional email (email defaults to null).
    public ServiceResponse createUser(final String requestId, final String username, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source) {
        return createUser(requestId, username, null, plainPassword, role, policyService, contextService, source, false);
    }

    public ServiceResponse createUser(final String requestId, final String username, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source, final boolean passwordChangeRequired) {
        return createUser(requestId, username, null, plainPassword, role, policyService, contextService, source, passwordChangeRequired);
    }

    public ServiceResponse createUser(final String requestId, final String username, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source) {
        return createUser(requestId, username, email, plainPassword, role, policyService, contextService, source, false);
    }

    public ServiceResponse createUser(final String requestId, final String username, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source, final boolean passwordChangeRequired) {
        return createUser(requestId, username, email, plainPassword, role, policyService, contextService, source, passwordChangeRequired, null);
    }

    /**
     * Creates a user, recording {@code actingUserId} as the principal of the {@code user_created}
     * audit event and the new user as the object it acted on. Pass null where the acting principal is
     * the dashboard session or startup, which the {@code source} already names; the new user is then
     * the subject, as it was before there was any other caller.
     */
    public ServiceResponse createUser(final String requestId, final String username, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source, final boolean passwordChangeRequired, final ObjectId actingUserId) {
        authorizeDashboardMutation(null, source, true);

        final UserEntity existing = findAnyByUsername(username);
        if(existing != null) {
            // A username belonging to a deactivated account stays reserved: reactivate it rather than
            // creating a duplicate user with the same username.
            if (existing.isDeactivated()) {
                return ServiceResponse.failure("A deactivated user already exists with that username. Reactivate that user instead.");
            }
            return ServiceResponse.failure("User already exists.");
        }

        final UserEntity userEntity = new UserEntity();
        userEntity.setUsername(username);
        userEntity.setEmail(email);
        userEntity.setPassword(passwordEncoder.encode(plainPassword));
        userEntity.setRole(role);
        userEntity.setPasswordChangeRequired(passwordChangeRequired);
        // A stable per-user key for the FPE_ENCRYPT_REPLACE strategy. It is generated once and never
        // changes so format-preserving encryption is deterministic for the user.
        userEntity.setFpeKey(EncryptionService.generateFpeKey());
        final ObjectId userId = save(userEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_CREATED,
                actingUserId == null ? userId : actingUserId, userId, source, "role: " + role);

        // Create the default policy.
        LOGGER.info("Inserting the default policy");
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setUserId(userId);
        policyEntity.setName("default");
        policyEntity.setPolicy(DefaultPolicy.json());
        policyEntity.setCreatedTimestamp(new Date());
        policyEntity.setLastUpdatedTimestamp(new Date());
        policyEntity.setRevision(0);
        policyEntity.setShared(false);
        policyEntity.setManaged(false);
        policyEntity.setDescription("Default policy");
        policyEntity.setNotes("Default policy for new users that can be modified or used as an example.");
        policyService.save(policyEntity);

        // Create the default context so the user has a usable context out of the box. Context names are
        // unique per user, so every new user gets their own "default". Treated as best-effort: a failure
        // here is logged but does not fail user creation (mirroring the default-policy handling above).
        LOGGER.info("Creating the default context for the new user");
        final ServiceResponse contextResponse = contextService.create("default", userId);
        if (contextResponse == null || !contextResponse.isSuccessful()) {
            LOGGER.warn("Unable to create the default context for user {}: {}", userId,
                    contextResponse != null ? contextResponse.getMessage() : "no response");
        }

        return ServiceResponse.success("User created.");

    }

    /** Returns whether the supplied plaintext password matches the user's stored (hashed) password. */
    public boolean passwordMatches(final UserEntity userEntity, final String plainPassword) {
        if (userEntity == null || userEntity.getPassword() == null || plainPassword == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, userEntity.getPassword());
    }

    /** Lists a page of all users, including deactivated ones (so the admin view can show them all). */
    public List<UserEntity> findAll(final int offset, final int limit) {
        return findAll(offset, limit, true);
    }

    /**
     * Lists a page of users sorted by email. When {@code includeDeactivated} is false, deactivated
     * users are excluded; when true, every user is returned so the admin view can show deactivated
     * accounts (clearly marked) alongside active ones.
     */
    public List<UserEntity> findAll(final int offset, final int limit, final boolean includeDeactivated) {

        final FindIterable<Document> documents = (includeDeactivated
                ? collection.find()
                : collection.find(Filters.ne("deactivated", true)))
                .sort(Sorts.ascending("username")).skip(offset).limit(limit);

        final List<UserEntity> userEntities = new ArrayList<>();

        for (final Document document : documents) {
            userEntities.add(UserEntity.fromDocument(document, encryptionService));
        }

        return userEntities;

    }

    /** Counts all users, including deactivated ones. */
    public int count() {
        return count(true);
    }

    /** Counts users; when {@code includeDeactivated} is false, deactivated users are excluded. */
    public int count(final boolean includeDeactivated) {
        return (int) (includeDeactivated
                ? collection.countDocuments()
                : collection.countDocuments(Filters.ne("deactivated", true)));
    }

    public ServiceResponse changePassword(final String requestId, final UserEntity userEntity, final String newPassword, final String source) {
        authorizeDashboardMutation(userEntity, source, false);

        if(userEntity == null) {

            return ServiceResponse.failure("User does not exist.");

        } else {

            userEntity.setPassword(passwordEncoder.encode(newPassword));
            // Changing the password satisfies any forced-reset requirement.
            userEntity.setPasswordChangeRequired(false);
            updateFields(userEntity, true, "password", "password_change_required");

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_PASSWORD_CHANGED, userEntity.getId(), userEntity.getId(), source, null);

            return ServiceResponse.success("Password changed.");

        }

    }

    public ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source) {
        authorizeDashboardMutation(userEntity, source, true);

        if (userEntity == null) {
            return ServiceResponse.failure("User does not exist.");

        } else {
            userEntity.setRole(newRole);
            updateFields(userEntity, true, "role");

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_ROLE_CHANGED, userEntity.getId(), userEntity.getId(), source, "role: " + newRole);

            return ServiceResponse.success("User role updated.");
        }

    }

    /** Returns the key assigned at account creation; missing key material is a configuration error. */
    public String ensureFpeKey(final UserEntity userEntity) {

        if (userEntity.getFpeKey() == null || userEntity.getFpeKey().isBlank()) {
            throw new IllegalStateException("Account FPE key is missing.");
        }
        return userEntity.getFpeKey();

    }

    /**
     * Deactivates a user. The account is marked deactivated (with the time of deactivation) but the
     * user record and <strong>all</strong> of the user's data (API keys, contexts, custom lists,
     * policies, redact lists, and redaction ledger) are retained, so the account can be reactivated
     * later (see {@link #reactivateUser(String, UserEntity, String)}) and so audit and ledger entries
     * that reference the user id still resolve to a name.
     *
     * <p>A deactivated user holds no active access: it is excluded from {@link #findByEmail(String)}
     * (which the login {@code UserDetailsService} and the cross-user {@code owner} lookup consult), and
     * the API authentication filter rejects its API keys by checking {@link #isDeactivated(ObjectId)}
     * live. The keys themselves are left untouched so reactivation restores access immediately without
     * resurrecting keys the user had separately deleted.
     *
     * <p>Crucially, deactivation never cascades to the user's data. Governance evidence in particular
     * (the user's policies and redaction ledger) is retained and stays resolvable to the retained user
     * record, so no admin action can silently destroy it. The audit event records that retention.
     */
    public void deactivateUser(final String requestId, final UserEntity userEntity, final String source) {
        authorizeDashboardMutation(userEntity, source, true);

        userEntity.setDeactivated(true);
        userEntity.setDeactivatedAt(new Date());
        if (!updateFields(userEntity, true, "deactivated", "deactivated_at")) return;

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_DEACTIVATED, userEntity.getId(), userEntity.getId(), source,
                "account deactivated; user data retained, including policies and redaction ledger (evidence preserved)");

    }

    /**
     * Reactivates a previously deactivated user, restoring sign-in and API access. The user's data was
     * never removed on deactivation, so reactivation returns the account to exactly its prior state.
     */
    public void reactivateUser(final String requestId, final UserEntity userEntity, final String source) {
        authorizeDashboardMutation(userEntity, source, true);

        userEntity.setDeactivated(false);
        userEntity.setDeactivatedAt(null);
        if (!updateFields(userEntity, true, "deactivated", "deactivated_at")) return;

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_REACTIVATED, userEntity.getId(), userEntity.getId(), source, null);

    }

    /**
     * Enables multi-factor authentication for a user by storing the verified TOTP secret and marking the
     * account enrolled. Called from the MFA enrollment flow only after the user has proven they can
     * generate a valid code from the secret.
     */
    public void enableMfa(final String requestId, final UserEntity userEntity, final String secret, final String source) {
        authorizeDashboardMutation(userEntity, source, false);

        userEntity.setMfaSecret(secret);
        userEntity.setMfaEnabled(true);
        userEntity.setMfaFailedAttempts(0);
        userEntity.setMfaLocked(false);
        updateFields(userEntity, true, "mfa_secret", "mfa_secret_key", "mfa_enabled", "mfa_failed_attempts", "mfa_locked");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_ENABLED, userEntity.getId(), userEntity.getId(), source, "MFA enabled via authenticator enrollment");

    }

    /**
     * Disables multi-factor authentication for a user and clears the enrolled secret. This is both the
     * admin reset path for a user who has lost their authenticator and the user's own opt-out: in either
     * case the user can enroll again from scratch. No-op (but still safe to call) when no MFA is enrolled.
     */
    public void disableMfa(final String requestId, final UserEntity userEntity, final String source) {
        authorizeDashboardMutation(userEntity, source, false);

        userEntity.setMfaEnabled(false);
        userEntity.setMfaSecret(null);
        userEntity.setMfaFailedAttempts(0);
        userEntity.setMfaLocked(false);
        updateFields(userEntity, true, "mfa_secret", "mfa_secret_key", "mfa_enabled", "mfa_failed_attempts", "mfa_locked");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_DISABLED, userEntity.getId(), userEntity.getId(), source, "MFA disabled and enrolled secret cleared");

    }

    /** Maximum consecutive failed MFA code attempts before the account is locked and needs an admin unlock. */
    public static final int MAX_MFA_ATTEMPTS = 5;

    /** Compare-and-set retries before falling back to counting without resolving the lock. */
    private static final int MFA_COUNT_ATTEMPTS = 25;

    /**
     * Records a failed MFA code entry. After {@link #MAX_MFA_ATTEMPTS} consecutive failures the account
     * is locked and can only be cleared by an administrator. Returns true if this failure locked it.
     */
    public boolean recordFailedMfaAttempt(final String requestId, final UserEntity userEntity, final String source) {

        for (int attempt = 0; attempt < MFA_COUNT_ATTEMPTS; attempt++) {

            final Document current = collection.find(Filters.and(Filters.eq("_id", userEntity.getId()),
                    Filters.eq("security_version", userEntity.getSecurityVersion()))).first();
            if (current == null) {
                return false;
            }
            if (current.getBoolean("mfa_locked", false)) {
                return false;
            }

            final int counted = current.getInteger("mfa_failed_attempts", 0);
            final int next = counted + 1;
            final boolean locks = next >= MAX_MFA_ATTEMPTS;

            // Compare-and-set: the count is in the filter, and the lock moves with it.
            final UpdateResult result = collection.updateOne(
                    Filters.and(
                            Filters.eq("_id", userEntity.getId()),
                            Filters.eq("mfa_failed_attempts", counted),
                            Filters.eq("security_version", userEntity.getSecurityVersion()),
                            Filters.ne("mfa_locked", true)),
                    Updates.combine(
                            Updates.set("mfa_failed_attempts", next),
                            Updates.set("mfa_locked", locks)));

            if (result.getMatchedCount() == 1) {

                userEntity.setMfaFailedAttempts(next);
                userEntity.setMfaLocked(locks);

                // Exactly one caller performs the transition, so the event is emitted once.
                if (locks) {
                    auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_LOCKED, userEntity.getId(),
                            userEntity.getId(), source,
                            "MFA locked after " + MAX_MFA_ATTEMPTS + " failed code attempts; requires an administrator to unlock");
                }

                return locks;

            }

        }

        // Rather than lose the attempt; the next failure establishes the lock.
        collection.updateOne(Filters.and(Filters.eq("_id", userEntity.getId()),
                Filters.eq("security_version", userEntity.getSecurityVersion())), Updates.inc("mfa_failed_attempts", 1));
        LOGGER.warn("Recorded a failed MFA attempt without resolving the lock state after {} attempts.",
                MFA_COUNT_ATTEMPTS);

        return false;

    }

    /** Clears the failed-attempt counter after a successful MFA verification. */
    public void resetMfaAttempts(final UserEntity userEntity) {
        if (userEntity.getMfaFailedAttempts() != 0) {
            userEntity.setMfaFailedAttempts(0);
            collection.updateOne(Filters.and(Filters.eq("_id", userEntity.getId()),
                    Filters.eq("security_version", userEntity.getSecurityVersion()), Filters.ne("mfa_locked", true)),
                    Updates.set("mfa_failed_attempts", 0));
        }
    }

    /** Records the step accepted, so that code cannot be presented again. */
    public boolean recordAcceptedMfaTimeStep(final UserEntity userEntity, final long timeStep) {

        // Accept only if the stored step is older, in one operation.
        final UpdateResult result = collection.updateOne(
                Filters.and(
                        Filters.eq("_id", userEntity.getId()),
                        Filters.eq("mfa_enabled", true),
                        Filters.ne("deactivated", true),
                        Filters.eq("security_version", userEntity.getSecurityVersion()),
                        Filters.ne("mfa_locked", true),
                        Filters.or(
                                Filters.exists("mfa_last_used_time_step", false),
                                Filters.lt("mfa_last_used_time_step", timeStep))),
                Updates.combine(
                        Updates.set("mfa_last_used_time_step", timeStep),
                        Updates.set("mfa_failed_attempts", 0)));

        final boolean accepted = result.getMatchedCount() == 1;

        if (accepted) {
            userEntity.setMfaLastUsedTimeStep(timeStep);
            userEntity.setMfaFailedAttempts(0);
        }

        return accepted;

    }

    /**
     * Clears an MFA lock and the failed-attempt counter so the user can enter a code again. This is the
     * administrator action that recovers a locked account; the user's enrollment is unchanged.
     */
    public void unlockMfa(final String requestId, final UserEntity userEntity, final String source) {
        authorizeDashboardMutation(userEntity, source, true);

        userEntity.setMfaLocked(false);
        userEntity.setMfaFailedAttempts(0);
        updateFields(userEntity, true, "mfa_locked", "mfa_failed_attempts");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_UNLOCKED, userEntity.getId(), userEntity.getId(), source, "MFA lock cleared by administrator");
    }

    /** Whole-record saves must never restore security state from a dashboard snapshot. */
    @Override
    public void update(final UserEntity user) {
        throw new UnsupportedOperationException("Use a field-specific account mutation.");
    }

    public void updateWebhook(final UserEntity user) {
        authorizeDashboardMutation(user, "webui", false);
        updateFields(user, false, "webhook_url", "webhook_secret", "webhook_secret_key");
    }

    private boolean updateFields(final UserEntity user, final boolean securityChange, final String... fields) {
        final Document serialized = user.toDocument(encryptionService);
        final Document selected = new Document();
        for (final String field : fields) selected.put(field, serialized.get(field));
        final Document update = new Document("$set", selected);
        if (securityChange) update.append("$inc", new Document("security_version", 1L));
        org.bson.conversions.Bson predicate = Filters.eq("_id", user.getId());
        if (selected.containsKey("deactivated")) {
            predicate = Filters.and(predicate, Filters.ne("deactivated", user.isDeactivated()));
        }
        return collection.updateOne(predicate, update).getMatchedCount() == 1;
    }

    private void authorizeDashboardMutation(final UserEntity target, final String source, final boolean adminOnly) {
        if (!"webui".equals(source)) return;
        final var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        final UserEntity actor = auth == null ? null : findByUsername(auth.getName());
        if (actor == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof ai.philterd.philter.api.security.DashboardPrincipal principal)
                || !principal.matches(actor)
                || actor.isMfaEnabled() && !ai.philterd.philter.views.MfaChallengeView.isSatisfied(
                        com.vaadin.flow.server.VaadinSession.getCurrent(), actor)
                || (adminOnly || target != null && !actor.getId().equals(target.getId()))
                    && !"admin".equalsIgnoreCase(actor.getRole())) {
            throw new org.springframework.security.access.AccessDeniedException("Current account authorization required.");
        }
    }

}
