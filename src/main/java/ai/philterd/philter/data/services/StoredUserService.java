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
import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.mfa.TotpService;
import ai.philterd.philter.services.policies.DefaultPolicy;
import ai.philterd.philter.services.webhook.WebhookSettings;
import ai.philterd.philter.utils.PathSafeNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Date;

/**
 * The rules for users, the same in every storage implementation: what a valid user, password, and email are,
 * that a new user gets a default policy and context, that the last active administrator stays, how passwords
 * are hashed and checked, MFA enrollment and its lock, webhook settings, and what is audited. An
 * implementation stores the users, their secrets encrypted, and makes their conditional changes atomic.
 */
public abstract class StoredUserService implements UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    /** The fields an account change writes together. */
    public enum Field {
        /** The role. */
        ROLE,
        /** The username and email. */
        EMAIL,
        /** Whether the user is deactivated, and when; written only if it changes. */
        DEACTIVATION,
        /** The webhook URL and secret. */
        WEBHOOK,
        /** The MFA secret being enrolled. */
        MFA_PENDING_SECRET,
        /** Every MFA field. */
        MFA
    }

    protected final AuditEventPublisher auditEventPublisher;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final TotpService totpService = new TotpService();

    protected StoredUserService(final AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Stores a new user, its secrets encrypted, and returns its id.
     *
     * @throws DuplicateRecordException when a user, active or deactivated, has its username.
     */
    protected abstract String insert(final UserEntity user);

    /**
     * Writes the given field's values from the user. {@link Field#DEACTIVATION} is written only if the stored
     * value differs; returns false when it did not, or when there is no such user.
     */
    protected abstract boolean write(final UserEntity user, final Field field);

    /** What {@link #writeKeepingAnAdministrator} did. */
    protected enum AdministratorChange {
        /** The change was written. */
        WRITTEN,
        /** Nothing was written: there is no such user, or the stored value already matched. */
        UNCHANGED,
        /** Nothing was written: the user is the only active administrator stored. */
        LAST_ADMINISTRATOR
    }

    /**
     * Writes a change that may take away the user's administrator access (a role change written as
     * {@link Field#ROLE}, or a deactivation written as {@link Field#DEACTIVATION}), unless the stored user is an
     * active administrator and no other active administrator is stored. The check and the write are atomic with
     * respect to every other call, on any instance, so of concurrent changes that would each remove an
     * administrator, those that would leave none are refused and at least one active administrator always remains.
     */
    protected abstract AdministratorChange writeKeepingAnAdministrator(final UserEntity user, final Field field);

    /**
     * Writes the user's MFA fields only if MFA is not already enabled, and returns whether it did. Of two
     * concurrent enrollments, one succeeds.
     */
    protected abstract boolean enrollMfa(final UserEntity user);

    /**
     * Records an accepted MFA code: only if MFA is enabled, not locked, and the code's time step is later than
     * the last one used, sets the last used step and clears the failures, and returns whether it did.
     */
    protected abstract boolean acceptMfaStep(final String userId, final long step);

    /** Adds one to the user's MFA failures atomically and returns the count, or 0 when MFA is not enabled. */
    protected abstract int countMfaFailure(final String userId);

    /** Locks MFA if it is not already locked, and returns whether it did. */
    protected abstract boolean lockMfa(final String userId);

    /** Unlocks a locked MFA and clears the failures, and returns whether it was locked. */
    protected abstract boolean unlockMfa(final String userId);

    /**
     * Writes a new password hash only if the stored hash is still {@code expectedHash}, so two concurrent changes
     * cannot both succeed against the same current password.
     */
    protected abstract boolean writePassword(final String userId, final String expectedHash, final String newHash,
            final boolean changeRequired);

    @Override
    public boolean isDeactivated(final String userId) {
        final UserEntity user = findOneById(userId);
        return user == null || user.isDeactivated();
    }

    @Override
    public int count() {
        return count(true);
    }

    @Override
    public ServiceResponse createUser(final String requestId, final String username, final String role,
                                      final PolicyDataService policyService, final ContextDataService contextService,
                                      final String source) {
        return createUser(requestId, username, null, role, policyService, contextService, source, null, null);
    }

    @Override
    public ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
                                      final PolicyDataService policyService, final ContextDataService contextService,
                                      final String source) {
        return createUser(requestId, username, email, role, policyService, contextService, source, null, null);
    }

    @Override
    public ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
                                      final PolicyDataService policyService, final ContextDataService contextService,
                                      final String source, final String actingUserId, final String actingApiKeyId) {
        return createUser(requestId, username, email, role, null, policyService, contextService, source,
                actingUserId, actingApiKeyId);
    }

    @Override
    public ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
                                      final String password, final PolicyDataService policyService,
                                      final ContextDataService contextService, final String source,
                                      final String actingUserId, final String actingApiKeyId) {

        if (password != null && UserService.passwordProblem(password) != null) {
            throw new IllegalArgumentException(UserService.passwordProblem(password));
        }
        if (!PathSafeNames.isPathSafe(username)) {
            throw new IllegalArgumentException("The username " + PathSafeNames.RULE + ".");
        }
        if (UserService.emailProblem(email) != null) {
            throw new IllegalArgumentException(UserService.emailProblem(email));
        }

        final UserEntity existing = findAnyByUsername(username);
        if(existing != null) {
            // A username belonging to a deactivated account stays reserved: reactivate it rather than
            // creating a duplicate user with the same username.
            if (existing.isDeactivated()) {
                return new ServiceResponse("A deactivated user already exists with that username. Reactivate that user instead.", false, 409, ErrorReasons.ALREADY_EXISTS);
            }
            return new ServiceResponse("User already exists.", false, 409, ErrorReasons.ALREADY_EXISTS);
        }

        final UserEntity userEntity = new UserEntity();
        userEntity.setUsername(username);
        userEntity.setEmail(normalizeEmail(email));
        userEntity.setRole(role);
        // A stable per-user key for the FPE_ENCRYPT_REPLACE strategy. It is generated once and never
        // changes so format-preserving encryption is deterministic for the user.
        userEntity.setFpeKey(EncryptionService.generateFpeKey());
        if (password != null) {
            userEntity.setPassword(passwordEncoder.encode(password));
            userEntity.setPasswordChangeRequired(true);
        }
        final String userId;
        try {
            userId = insert(userEntity);
        } catch (final DuplicateRecordException ex) {
            // Another request created the same username since the check above.
            return new ServiceResponse("User already exists.", false, 409, ErrorReasons.ALREADY_EXISTS);
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_CREATED,
                actingUserId == null ? userId : actingUserId, userId, source, withApiKey("role: " + role, actingApiKeyId));
        if (password != null) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_PASSWORD_SET,
                    actingUserId == null ? userId : actingUserId, userId, source,
                    withApiKey("change_required: true", actingApiKeyId));
        }

        // Create the default policy.
        LOGGER.info("Inserting the default policy");
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setUserId(userId);
        policyEntity.setName("default");
        policyEntity.setAuthorId(actingUserId == null ? userId : actingUserId);
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

    @Override
    public ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source) {
        return setUserRole(requestId, userEntity, newRole, source, null, null);
    }

    @Override
    public ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source, final String actingUserId, final String actingApiKeyId) {

        if (userEntity == null) {
            return ServiceResponse.failure("User does not exist.");

        } else {
            final String previousRole = userEntity.getRole();
            userEntity.setRole(newRole);
            if (ROLE_ADMIN.equalsIgnoreCase(newRole)) {
                write(userEntity, Field.ROLE);
            } else if (writeKeepingAnAdministrator(userEntity, Field.ROLE) == AdministratorChange.LAST_ADMINISTRATOR) {
                userEntity.setRole(previousRole);
                return new ServiceResponse(LAST_ADMIN_MESSAGE, false, 409, ErrorReasons.LAST_ADMIN);
            }

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_ROLE_CHANGED,
                    actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source, withApiKey("role: " + newRole, actingApiKeyId));

            return ServiceResponse.success("User role updated.");
        }

    }

    /** The address as stored: trimmed, or {@code null} for none. */
    protected static String normalizeEmail(final String email) {
        return email == null || email.isBlank() ? null : email.trim();
    }

    @Override
    public ServiceResponse setEmail(final String requestId, final UserEntity userEntity, final String email,
                                    final String source, final String actingUserId, final String actingApiKeyId) {

        final String problem = UserService.emailProblem(email);
        if (problem != null) {
            return ServiceResponse.failure(problem);
        }

        final String normalized = normalizeEmail(email);
        userEntity.setEmail(normalized);
        write(userEntity, Field.EMAIL);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_EMAIL_CHANGED,
                actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source,
                withApiKey(normalized == null ? "email: removed" : "email: changed", actingApiKeyId));

        return ServiceResponse.success("Email updated.");

    }

    /** Appends the acting API key to audit details, so an API change names the credential that made it. */
    private static String withApiKey(final String details, final String actingApiKeyId) {
        if (actingApiKeyId == null) {
            return details;
        }
        return details == null ? "api_key: " + actingApiKeyId : details + ", api_key: " + actingApiKeyId;
    }

    @Override
    public boolean isLastActiveAdmin(final UserEntity userEntity) {
        return ROLE_ADMIN.equalsIgnoreCase(userEntity.getRole()) && !userEntity.isDeactivated()
                && countActiveAdmins() <= 1;
    }

    @Override
    public String ensureFpeKey(final UserEntity userEntity) {

        if (userEntity.getFpeKey() == null || userEntity.getFpeKey().isBlank()) {
            throw new IllegalStateException("Account FPE key is missing.");
        }
        return userEntity.getFpeKey();

    }

    @Override
    public ServiceResponse deactivateUser(final String requestId, final UserEntity userEntity, final String source) {
        return deactivateUser(requestId, userEntity, source, null, null);
    }

    @Override
    public ServiceResponse deactivateUser(final String requestId, final UserEntity userEntity, final String source, final String actingUserId, final String actingApiKeyId) {

        final boolean previouslyDeactivated = userEntity.isDeactivated();
        final Date previousDeactivatedAt = userEntity.getDeactivatedAt();
        userEntity.setDeactivated(true);
        userEntity.setDeactivatedAt(new Date());
        final AdministratorChange change = writeKeepingAnAdministrator(userEntity, Field.DEACTIVATION);
        if (change != AdministratorChange.WRITTEN) {
            userEntity.setDeactivated(previouslyDeactivated);
            userEntity.setDeactivatedAt(previousDeactivatedAt);
            return change == AdministratorChange.LAST_ADMINISTRATOR
                    ? new ServiceResponse(LAST_ADMIN_MESSAGE, false, 409, ErrorReasons.LAST_ADMIN)
                    : ServiceResponse.failure("User is already deactivated.");
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_DEACTIVATED,
                actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source,
                withApiKey("account deactivated; user data retained, including policies and redaction ledger (evidence preserved)", actingApiKeyId));

        return ServiceResponse.success("User deactivated.");

    }

    @Override
    public ServiceResponse reactivateUser(final String requestId, final UserEntity userEntity, final String source) {
        return reactivateUser(requestId, userEntity, source, null, null);
    }

    @Override
    public ServiceResponse reactivateUser(final String requestId, final UserEntity userEntity, final String source, final String actingUserId, final String actingApiKeyId) {

        userEntity.setDeactivated(false);
        userEntity.setDeactivatedAt(null);
        if (!write(userEntity, Field.DEACTIVATION)) {
            return ServiceResponse.failure("User is already active.");
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_REACTIVATED,
                actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("User reactivated.");

    }

    @Override
    public ServiceResponse setWebhook(final String requestId, final UserEntity user, final String url,
                                      final String secret, final String allowlist, final String source,
                                      final String actingUserId, final String actingApiKeyId) {

        // Without a new secret the one already set is kept, so the URL can change without rotating it.
        final boolean keepsSecret = (secret == null || secret.isEmpty())
                && user.getWebhookSecret() != null && !user.getWebhookSecret().isEmpty();
        final String effectiveSecret = keepsSecret ? user.getWebhookSecret() : secret;

        final String problem = WebhookSettings.validate(url, effectiveSecret, allowlist);
        if (problem != null) {
            return ServiceResponse.failure(problem);
        }

        user.setWebhookUrl(url.trim());
        user.setWebhookSecret(effectiveSecret);
        write(user, Field.WEBHOOK);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.WEBHOOK_CONFIGURED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source,
                withApiKey(keepsSecret ? "secret: kept" : "secret: set", actingApiKeyId));

        return ServiceResponse.success("Webhook saved.");
    }

    @Override
    public ServiceResponse removeWebhook(final String requestId, final UserEntity user, final String source,
                                         final String actingUserId, final String actingApiKeyId) {

        user.setWebhookUrl(null);
        user.setWebhookSecret(null);
        write(user, Field.WEBHOOK);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.WEBHOOK_REMOVED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("Webhook removed.");
    }

    @Override
    public UserEntity authenticate(final String username, final String plainPassword) {
        final UserEntity user = username == null ? null : findByUsername(username);
        if (user == null || user.getPassword() == null || plainPassword == null) {
            passwordEncoder.matches(plainPassword == null ? "" : plainPassword, timingHash());
            return null;
        }
        return passwordEncoder.matches(plainPassword, user.getPassword()) ? user : null;
    }

    private volatile String timingHash;

    /** A hash of a random value, compared against when there is no real hash, so a miss costs the same. */
    private String timingHash() {
        if (timingHash == null) {
            timingHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        }
        return timingHash;
    }

    @Override
    public boolean passwordMatches(final UserEntity user, final String plainPassword) {
        if (user == null || user.getPassword() == null || plainPassword == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, user.getPassword());
    }

    @Override
    public ServiceResponse changeOwnPassword(final String requestId, final UserEntity user, final String currentPassword,
                                             final String newPassword, final String source, final String actingApiKeyId) {

        if (user.getPassword() == null) {
            return new ServiceResponse("The user has no password. An administrator sets the first one.", false, 409);
        }
        if (!passwordMatches(user, currentPassword)) {
            return new ServiceResponse("The current password is not correct.", false, 403, ErrorReasons.WRONG_PASSWORD);
        }
        final String problem = UserService.passwordProblem(newPassword);
        if (problem != null) {
            return new ServiceResponse(problem, false, 400);
        }
        if (passwordMatches(user, newPassword)) {
            return new ServiceResponse("The new password must differ from the current one.", false, 400);
        }

        if (!changePassword(user, passwordEncoder.encode(newPassword), false)) {
            return new ServiceResponse("The password was changed by another request. Try again.", false, 409, ErrorReasons.CHANGED_CONCURRENTLY);
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_PASSWORD_CHANGED, user.getId(), user.getId(),
                source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("Password changed.");

    }

    @Override
    public ServiceResponse setPassword(final String requestId, final UserEntity user, final String newPassword,
                                       final boolean changeRequired, final String source,
                                       final String actingUserId, final String actingApiKeyId) {

        final String problem = UserService.passwordProblem(newPassword);
        if (problem != null) {
            return new ServiceResponse(problem, false, 400);
        }

        final boolean replacing = user.getPassword() != null;
        if (!changePassword(user, passwordEncoder.encode(newPassword), changeRequired)) {
            return new ServiceResponse("The password was changed by another request. Try again.", false, 409, ErrorReasons.CHANGED_CONCURRENTLY);
        }

        auditEventPublisher.auditEvent(requestId,
                replacing ? AuditLogEvent.USER_PASSWORD_RESET : AuditLogEvent.USER_PASSWORD_SET,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source,
                withApiKey("change_required: " + changeRequired, actingApiKeyId));

        return ServiceResponse.success(replacing ? "Password reset." : "Password set.");

    }

    @Override
    public String startMfaEnrollment(final UserEntity user) {
        if (user.isMfaEnabled()) {
            return null;
        }
        final String secret = totpService.generateSecret();
        user.setMfaPendingSecret(secret);
        write(user, Field.MFA_PENDING_SECRET);
        return secret;
    }

    @Override
    public String mfaOtpauthUri(final UserEntity user, final String secret) {
        return totpService.otpauthUri(user.getUsername(), secret);
    }

    @Override
    public ServiceResponse confirmMfaEnrollment(final String requestId, final UserEntity user, final String code,
                                                final String source, final String actingApiKeyId) {

        if (user.isMfaEnabled()) {
            return new ServiceResponse("MFA is already enrolled.", false, 409, ErrorReasons.MFA_ALREADY_ENROLLED);
        }
        if (user.getMfaPendingSecret() == null) {
            return new ServiceResponse("Start enrollment first, with POST /api/users/me/mfa.", false, 409);
        }
        final long step = totpService.matchingTimeStep(user.getMfaPendingSecret(), code);
        if (step == TotpService.NO_MATCH) {
            return new ServiceResponse("The code is not valid.", false, 400, ErrorReasons.INVALID_CODE);
        }

        user.setMfaEnabled(true);
        user.setMfaSecret(user.getMfaPendingSecret());
        user.setMfaPendingSecret(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLocked(false);
        user.setMfaLastUsedTimeStep(step);
        // Only one of two concurrent confirmations enrolls.
        if (!enrollMfa(user)) {
            return new ServiceResponse("MFA is already enrolled.", false, 409, ErrorReasons.MFA_ALREADY_ENROLLED);
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_ENROLLED, user.getId(), user.getId(),
                source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("MFA enrolled.");

    }

    @Override
    public MfaCheck checkMfaCode(final String requestId, final UserEntity user, final String code, final String source) {

        if (!user.isMfaEnabled()) {
            return MfaCheck.REFUSED;
        }
        if (user.isMfaLocked()) {
            return MfaCheck.LOCKED;
        }

        final long step = totpService.matchingTimeStep(user.getMfaSecret(), code);
        if (step != TotpService.NO_MATCH) {
            if (acceptMfaStep(user.getId(), step)) {
                user.setMfaLastUsedTimeStep(step);
                user.setMfaFailedAttempts(0);
                return MfaCheck.ACCEPTED;
            }
        }

        return recordBadMfaCode(requestId, user, source) ? MfaCheck.LOCKED : MfaCheck.REFUSED;

    }

    /** Counts a bad code atomically, and locks the user at the limit, auditing the lock once. */
    private boolean recordBadMfaCode(final String requestId, final UserEntity user, final String source) {

        if (countMfaFailure(user.getId()) < MAX_MFA_ATTEMPTS) {
            return false;
        }

        if (lockMfa(user.getId())) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_LOCKED, user.getId(), user.getId(), source,
                    "after " + MAX_MFA_ATTEMPTS + " consecutive bad codes");
        }
        user.setMfaLocked(true);
        return true;

    }

    @Override
    public ServiceResponse removeMfa(final String requestId, final UserEntity user, final String source,
                                     final String actingUserId, final String actingApiKeyId) {

        if (!user.isMfaEnabled() && user.getMfaPendingSecret() == null) {
            return new ServiceResponse("MFA is not enrolled.", false, 409, ErrorReasons.MFA_NOT_ENROLLED);
        }

        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setMfaPendingSecret(null);
        user.setMfaFailedAttempts(0);
        user.setMfaLocked(false);
        user.setMfaLastUsedTimeStep(0);
        write(user, Field.MFA);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_REMOVED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("MFA removed.");

    }

    @Override
    public ServiceResponse unlockMfa(final String requestId, final UserEntity user, final String source,
                                     final String actingUserId, final String actingApiKeyId) {

        if (!unlockMfa(user.getId())) {
            return new ServiceResponse("The user is not locked.", false, 409);
        }
        user.setMfaLocked(false);
        user.setMfaFailedAttempts(0);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_UNLOCKED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("MFA unlocked.");

    }

    private boolean changePassword(final UserEntity user, final String newHash, final boolean changeRequired) {
        final boolean written = writePassword(user.getId(), user.getPassword(), newHash, changeRequired);
        if (written) {
            user.setPassword(newHash);
            user.setPasswordChangeRequired(changeRequired);
        }
        return written;
    }

}
