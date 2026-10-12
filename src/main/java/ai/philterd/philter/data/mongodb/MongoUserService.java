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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.mfa.TotpService;
import ai.philterd.philter.services.policies.DefaultPolicy;
import ai.philterd.philter.services.webhook.WebhookSettings;
import ai.philterd.philter.utils.PathSafeNames;
import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
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

/** MongoDB implementation of {@link UserService}. */
public class MongoUserService extends AbstractEncryptedService<UserEntity> implements UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private final TotpService totpService = new TotpService();

    public MongoUserService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "users", encryptionService, auditEventPublisher);

        ensureIndex(Indexes.ascending("username"), new com.mongodb.client.model.IndexOptions().unique(true));

    }

    @Override
    public UserEntity findByUsername(final String username) {
        final Document document = collection.find(
                Filters.and(Filters.eq("username", username), Filters.ne("deactivated", true))).first();
        if (document != null) {
            return UserDocuments.fromDocument(document, encryptionService);
        }
        return null;
    }

    @Override
    public UserEntity findAnyByUsername(final String username) {
        final Document document = collection.find(Filters.eq("username", username)).first();
        if (document != null) {
            return UserDocuments.fromDocument(document, encryptionService);
        }
        return null;
    }

    @Override
    public boolean isDeactivated(final String userId) {
        final Document document = collection.find(Filters.eq("_id", MongoIds.objectId(userId)))
                .projection(Projections.include("deactivated")).first();
        return document == null || document.getBoolean("deactivated", false);
    }

    @Override
    public UserEntity findOneById(final String id) {

        final Document query = new Document("_id", MongoIds.objectId(id));

        final Document document = collection.find(query).first();

        if(document != null) {

            return UserDocuments.fromDocument(document, encryptionService);

        } else {

            return null;

        }

    }

    @Override
    public Map<String, String> findUsernamesByIds(final Collection<String> ids) {

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<String, String> usernamesById = new HashMap<>();
        for (final Document document : collection.find(Filters.in("_id", MongoIds.objectIds(ids)))) {
            final UserEntity user = UserDocuments.fromDocument(document, encryptionService);
            usernamesById.put(user.getId(), user.getUsername());
        }

        return usernamesById;

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
            userId = save(userEntity);
        } catch (final MongoWriteException ex) {
            // Another request created the same username since the check above.
            if (ex.getError().getCategory() == ErrorCategory.DUPLICATE_KEY) {
                return new ServiceResponse("User already exists.", false, 409, ErrorReasons.ALREADY_EXISTS);
            }
            throw ex;
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
    public List<UserEntity> findAll(final int offset, final int limit) {
        return findAll(offset, limit, true);
    }

    @Override
    public List<UserEntity> findAll(final int offset, final int limit, final boolean includeDeactivated) {

        final FindIterable<Document> documents = (includeDeactivated
                ? collection.find()
                : collection.find(Filters.ne("deactivated", true)))
                .sort(Sorts.ascending("username")).skip(offset).limit(limit);

        final List<UserEntity> userEntities = new ArrayList<>();

        for (final Document document : documents) {
            userEntities.add(UserDocuments.fromDocument(document, encryptionService));
        }

        return userEntities;

    }

    @Override
    public Listings.Page<UserEntity> list(final String q, final String role, final Boolean active,
                                          final Listings.Sort sort, final int offset, final int limit) {
        return MongoListings.page(collection, MongoListings.all(MongoListings.contains("username", q),
                        role == null ? null : Filters.eq("role", role),
                        active == null ? null : active ? Filters.ne("deactivated", true) : Filters.eq("deactivated", true)),
                sort, offset, limit, document -> UserDocuments.fromDocument(document, encryptionService));
    }

    @Override
    public int count() {
        return count(true);
    }

    @Override
    public int count(final boolean includeDeactivated) {
        return (int) (includeDeactivated
                ? collection.countDocuments()
                : collection.countDocuments(Filters.ne("deactivated", true)));
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
            if (!ROLE_ADMIN.equalsIgnoreCase(newRole) && isLastActiveAdmin(userEntity)) {
                return new ServiceResponse(LAST_ADMIN_MESSAGE, false, 409, ErrorReasons.LAST_ADMIN);
            }

            userEntity.setRole(newRole);
            updateFields(userEntity, "role");

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_ROLE_CHANGED,
                    actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source, withApiKey("role: " + newRole, actingApiKeyId));

            return ServiceResponse.success("User role updated.");
        }

    }

    /** The address as stored: trimmed, or {@code null} for none. */
    private static String normalizeEmail(final String email) {
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
        // The username is written too: records from before usernames existed kept the login under
        // "email", and are read that way while "username" is absent, so changing the email alone would
        // change how such a user signs in.
        updateFields(userEntity, "username", "email");

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
    public long countActiveAdmins() {
        return collection.countDocuments(Filters.and(
                Filters.regex("role", "^" + ROLE_ADMIN + "$", "i"), Filters.ne("deactivated", true)));
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

        if (isLastActiveAdmin(userEntity)) {
            return new ServiceResponse(LAST_ADMIN_MESSAGE, false, 409, ErrorReasons.LAST_ADMIN);
        }

        userEntity.setDeactivated(true);
        userEntity.setDeactivatedAt(new Date());
        if (!updateFields(userEntity, "deactivated", "deactivated_at")) {
            return ServiceResponse.failure("User is already deactivated.");
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
        if (!updateFields(userEntity, "deactivated", "deactivated_at")) {
            return ServiceResponse.failure("User is already active.");
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_REACTIVATED,
                actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("User reactivated.");

    }

    @Override
    public void update(final UserEntity user) {
        throw new UnsupportedOperationException("Use a field-specific account mutation.");
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
        updateFields(user, "webhook_url", "webhook_secret", "webhook_secret_key");

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
        updateFields(user, "webhook_url", "webhook_secret", "webhook_secret_key");

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

        if (!writePassword(user, passwordEncoder.encode(newPassword), false)) {
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
        if (!writePassword(user, passwordEncoder.encode(newPassword), changeRequired)) {
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
        updateFields(user, "mfa_pending_secret", "mfa_pending_secret_key");
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
        final Document serialized = UserDocuments.toDocument(user, encryptionService);
        final Document selected = new Document();
        for (final String field : List.of("mfa_enabled", "mfa_secret", "mfa_secret_key", "mfa_pending_secret",
                "mfa_pending_secret_key", "mfa_failed_attempts", "mfa_locked", "mfa_last_used_time_step")) {
            selected.put(field, serialized.get(field));
        }
        // Only one of two concurrent confirmations enrolls.
        if (collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(user.getId())), Filters.ne("mfa_enabled", true)),
                new Document("$set", selected)).getMatchedCount() != 1) {
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
            final boolean accepted = collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(user.getId())),
                            Filters.eq("mfa_enabled", true), Filters.ne("mfa_locked", true),
                            Filters.or(Filters.lt("mfa_last_used_time_step", step),
                                    Filters.exists("mfa_last_used_time_step", false))),
                    new Document("$set", new Document("mfa_last_used_time_step", step).append("mfa_failed_attempts", 0)))
                    .getMatchedCount() == 1;
            if (accepted) {
                user.setMfaLastUsedTimeStep(step);
                user.setMfaFailedAttempts(0);
                return MfaCheck.ACCEPTED;
            }
        }

        return recordBadMfaCode(requestId, user, source) ? MfaCheck.LOCKED : MfaCheck.REFUSED;

    }

    /** Counts a bad code atomically, and locks the user at the limit, auditing the lock once. */
    private boolean recordBadMfaCode(final String requestId, final UserEntity user, final String source) {

        final Document after = collection.findOneAndUpdate(
                Filters.and(Filters.eq("_id", MongoIds.objectId(user.getId())), Filters.eq("mfa_enabled", true)),
                new Document("$inc", new Document("mfa_failed_attempts", 1)),
                new com.mongodb.client.model.FindOneAndUpdateOptions().returnDocument(com.mongodb.client.model.ReturnDocument.AFTER));
        if (after == null || after.getInteger("mfa_failed_attempts", 0) < MAX_MFA_ATTEMPTS) {
            return false;
        }

        if (collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(user.getId())), Filters.ne("mfa_locked", true)),
                new Document("$set", new Document("mfa_locked", true))).getMatchedCount() == 1) {
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
        updateFields(user, "mfa_enabled", "mfa_secret", "mfa_secret_key", "mfa_pending_secret", "mfa_pending_secret_key",
                "mfa_failed_attempts", "mfa_locked", "mfa_last_used_time_step");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_REMOVED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("MFA removed.");

    }

    @Override
    public ServiceResponse unlockMfa(final String requestId, final UserEntity user, final String source,
                                     final String actingUserId, final String actingApiKeyId) {

        if (collection.updateOne(Filters.and(Filters.eq("_id", MongoIds.objectId(user.getId())), Filters.eq("mfa_locked", true)),
                new Document("$set", new Document("mfa_locked", false).append("mfa_failed_attempts", 0)))
                .getMatchedCount() != 1) {
            return new ServiceResponse("The user is not locked.", false, 409);
        }
        user.setMfaLocked(false);
        user.setMfaFailedAttempts(0);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_MFA_UNLOCKED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("MFA unlocked.");

    }

    /**
     * Writes a new hash only if the stored one is still the one this request read, so two concurrent
     * changes cannot both succeed against the same current password.
     */
    private boolean writePassword(final UserEntity user, final String newHash, final boolean changeRequired) {
        final boolean written = collection.updateOne(
                Filters.and(Filters.eq("_id", MongoIds.objectId(user.getId())), Filters.eq("password", user.getPassword())),
                new Document("$set", new Document("password", newHash).append("password_change_required", changeRequired)))
                .getMatchedCount() == 1;
        if (written) {
            user.setPassword(newHash);
            user.setPasswordChangeRequired(changeRequired);
        }
        return written;
    }

    private boolean updateFields(final UserEntity user, final String... fields) {
        final Document serialized = UserDocuments.toDocument(user, encryptionService);
        final Document selected = new Document();
        for (final String field : fields) selected.put(field, serialized.get(field));
        final Document update = new Document("$set", selected);
        org.bson.conversions.Bson predicate = Filters.eq("_id", MongoIds.objectId(user.getId()));
        if (selected.containsKey("deactivated")) {
            predicate = Filters.and(predicate, Filters.ne("deactivated", user.isDeactivated()));
        }
        return collection.updateOne(predicate, update).getMatchedCount() == 1;
    }

    @Override
    protected Document toDocument(final UserEntity entity) {
        return UserDocuments.toDocument(entity, encryptionService);
    }

}
