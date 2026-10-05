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
import ai.philterd.philter.services.webhook.WebhookSettings;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UserService extends AbstractEncryptedService<UserEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    /** The role that grants administrator rights. */
    public static final String ROLE_ADMIN = "admin";

    /** The role every other user has. */
    public static final String ROLE_USER = "user";

    public static final String LAST_ADMIN_MESSAGE =
            "This is the last active administrator. Make another user an administrator first.";

    /** Fewer characters are refused. */
    public static final int MIN_PASSWORD_CHARACTERS = 16;
    /** bcrypt reads at most 72 bytes, so a longer password would be silently truncated. */
    public static final int MAX_PASSWORD_BYTES = 72;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "users", encryptionService, auditEventPublisher);

        ensureIndex(Indexes.ascending("username"), new com.mongodb.client.model.IndexOptions().unique(true));

    }

    /**
     * Looks up an <strong>active</strong> (not deactivated) user by username. Deactivated users are
     * excluded so their keys cannot be used and they cannot be targeted via the cross-user {@code owner}
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

    public ServiceResponse createUser(final String requestId, final String username, final String role,
                                      final PolicyDataService policyService, final ContextDataService contextService,
                                      final String source) {
        return createUser(requestId, username, null, role, policyService, contextService, source, null, null);
    }

    public ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
                                      final PolicyDataService policyService, final ContextDataService contextService,
                                      final String source) {
        return createUser(requestId, username, email, role, policyService, contextService, source, null, null);
    }

    /**
     * Creates a user, with a default policy and context. {@code actingUserId} is recorded as the principal
     * of the {@code user_created} audit event, or the new user itself when null (startup), and
     * {@code actingApiKeyId}, if any, in the details. The user has no password, so it can only use API keys.
     */
    public ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
                                      final PolicyDataService policyService, final ContextDataService contextService,
                                      final String source, final ObjectId actingUserId, final ObjectId actingApiKeyId) {
        return createUser(requestId, username, email, role, null, policyService, contextService, source,
                actingUserId, actingApiKeyId);
    }

    /**
     * As above, with an optional password, which the user must change at next sign-in because someone
     * else chose it. Setting it is audited as {@code user_password_set}, without the password.
     *
     * @throws IllegalArgumentException if the password is not acceptable; see {@link #passwordProblem(String)}.
     */
    public ServiceResponse createUser(final String requestId, final String username, final String email, final String role,
                                      final String password, final PolicyDataService policyService,
                                      final ContextDataService contextService, final String source,
                                      final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        if (password != null && passwordProblem(password) != null) {
            throw new IllegalArgumentException(passwordProblem(password));
        }

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
        userEntity.setRole(role);
        // A stable per-user key for the FPE_ENCRYPT_REPLACE strategy. It is generated once and never
        // changes so format-preserving encryption is deterministic for the user.
        userEntity.setFpeKey(EncryptionService.generateFpeKey());
        if (password != null) {
            userEntity.setPassword(passwordEncoder.encode(password));
            userEntity.setPasswordChangeRequired(true);
        }
        final ObjectId userId = save(userEntity);

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


    /** Lists a page of all users, including deactivated ones. */
    public List<UserEntity> findAll(final int offset, final int limit) {
        return findAll(offset, limit, true);
    }

    /**
     * Lists a page of users sorted by email. When {@code includeDeactivated} is false, deactivated
     * users are excluded; when true, every user is returned, deactivated ones included.
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


    public ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source) {
        return setUserRole(requestId, userEntity, newRole, source, null, null);
    }

    /**
     * Sets a user's role, recording {@code actingUserId} as the audit principal (the target user when
     * null) and {@code actingApiKeyId}, if any, in the details. Refuses to demote the last active
     * administrator.
     */
    public ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source, final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        if (userEntity == null) {
            return ServiceResponse.failure("User does not exist.");

        } else {
            if (!ROLE_ADMIN.equalsIgnoreCase(newRole) && isLastActiveAdmin(userEntity)) {
                return ServiceResponse.failure(LAST_ADMIN_MESSAGE);
            }

            userEntity.setRole(newRole);
            updateFields(userEntity, "role");

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_ROLE_CHANGED,
                    actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source, withApiKey("role: " + newRole, actingApiKeyId));

            return ServiceResponse.success("User role updated.");
        }

    }

    /** Appends the acting API key to audit details, so an API change names the credential that made it. */
    private static String withApiKey(final String details, final ObjectId actingApiKeyId) {
        if (actingApiKeyId == null) {
            return details;
        }
        return details == null ? "api_key: " + actingApiKeyId : details + ", api_key: " + actingApiKeyId;
    }

    /** Counts active (not deactivated) administrators. */
    public long countActiveAdmins() {
        return collection.countDocuments(Filters.and(
                Filters.regex("role", "^" + ROLE_ADMIN + "$", "i"), Filters.ne("deactivated", true)));
    }

    /**
     * Whether removing this user's administrator rights would leave no active administrator. The check
     * and the change that follows are separate operations, so two concurrent removals can still race.
     */
    public boolean isLastActiveAdmin(final UserEntity userEntity) {
        return ROLE_ADMIN.equalsIgnoreCase(userEntity.getRole()) && !userEntity.isDeactivated()
                && countActiveAdmins() <= 1;
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
     * <p>A deactivated user holds no active access: it is excluded from {@link #findByUsername(String)}
     * (which the cross-user {@code owner} lookup consults), and
     * the API authentication filter rejects its API keys by checking {@link #isDeactivated(ObjectId)}
     * live. The keys themselves are left untouched so reactivation restores access immediately without
     * resurrecting keys the user had separately deleted.
     *
     * <p>Crucially, deactivation never cascades to the user's data. Governance evidence in particular
     * (the user's policies and redaction ledger) is retained and stays resolvable to the retained user
     * record, so no admin action can silently destroy it. The audit event records that retention.
     */
    public ServiceResponse deactivateUser(final String requestId, final UserEntity userEntity, final String source) {
        return deactivateUser(requestId, userEntity, source, null, null);
    }

    /**
     * Deactivates a user as above, recording {@code actingUserId} as the audit principal (the target user
     * when null) and {@code actingApiKeyId}, if any, in the details. Refuses to deactivate the last
     * active administrator.
     */
    public ServiceResponse deactivateUser(final String requestId, final UserEntity userEntity, final String source, final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        if (isLastActiveAdmin(userEntity)) {
            return ServiceResponse.failure(LAST_ADMIN_MESSAGE);
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

    /**
     * Reactivates a previously deactivated user, restoring API access. The user's data was
     * never removed on deactivation, so reactivation returns the account to exactly its prior state.
     */
    public ServiceResponse reactivateUser(final String requestId, final UserEntity userEntity, final String source) {
        return reactivateUser(requestId, userEntity, source, null, null);
    }

    /**
     * Reactivates a user as above, recording {@code actingUserId} as the audit principal (the target user
     * when null) and {@code actingApiKeyId}, if any, in the details.
     */
    public ServiceResponse reactivateUser(final String requestId, final UserEntity userEntity, final String source, final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        userEntity.setDeactivated(false);
        userEntity.setDeactivatedAt(null);
        if (!updateFields(userEntity, "deactivated", "deactivated_at")) {
            return ServiceResponse.failure("User is already active.");
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_REACTIVATED,
                actingUserId == null ? userEntity.getId() : actingUserId, userEntity.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("User reactivated.");

    }







    /** Whole-record saves could overwrite a concurrent change to another field; update fields individually. */
    @Override
    public void update(final UserEntity user) {
        throw new UnsupportedOperationException("Use a field-specific account mutation.");
    }

    /**
     * Validates and saves a user's webhook URL and secret, then audits {@code webhook_configured} with
     * {@code actingUserId} as the principal (the user when null) and {@code actingApiKeyId}, if any, in
     * the details. The URL and secret are never audited.
     *
     * @param allowlist The administrator's webhook destination allowlist, or {@code null} for none.
     */
    public ServiceResponse setWebhook(final String requestId, final UserEntity user, final String url,
                                      final String secret, final String allowlist, final String source,
                                      final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        final String problem = WebhookSettings.validate(url, secret, allowlist);
        if (problem != null) {
            return ServiceResponse.failure(problem);
        }

        user.setWebhookUrl(url.trim());
        user.setWebhookSecret(secret);
        updateFields(user, "webhook_url", "webhook_secret", "webhook_secret_key");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.WEBHOOK_CONFIGURED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("Webhook saved.");
    }

    /** Removes a user's webhook and audits {@code webhook_removed}, recording the acting principal as above. */
    public ServiceResponse removeWebhook(final String requestId, final UserEntity user, final String source,
                                         final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        user.setWebhookUrl(null);
        user.setWebhookSecret(null);
        updateFields(user, "webhook_url", "webhook_secret", "webhook_secret_key");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.WEBHOOK_REMOVED,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("Webhook removed.");
    }

    /**
     * Why a password is not acceptable, or {@code null} when it is: at least
     * {@value #MIN_PASSWORD_CHARACTERS} characters and at most {@value #MAX_PASSWORD_BYTES} UTF-8 bytes.
     */
    public static String passwordProblem(final String password) {
        if (password == null || password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARACTERS) {
            return "The password must be at least " + MIN_PASSWORD_CHARACTERS + " characters.";
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return "The password must be at most " + MAX_PASSWORD_BYTES + " bytes in UTF-8.";
        }
        return null;
    }

    /**
     * The active user with this username and password, or {@code null}. A missing user, a deactivated
     * user, a user without a password, and a wrong password all return {@code null}, and each runs one
     * bcrypt comparison, so neither the result nor the time taken tells a caller which usernames exist.
     */
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

    /** Whether the plaintext matches the user's password. Always false for a user without one. */
    public boolean passwordMatches(final UserEntity user, final String plainPassword) {
        if (user == null || user.getPassword() == null || plainPassword == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, user.getPassword());
    }

    /**
     * Changes the user's own password, which requires the current one, and clears any forced change.
     * Audited as {@code user_password_changed}, without either password. The caller revokes the user's
     * session keys.
     *
     * @return a failure with status 400 (the new password is unacceptable or unchanged), 403 (the current
     * password is wrong), or 409 (the user has no password, or it changed concurrently).
     */
    public ServiceResponse changeOwnPassword(final String requestId, final UserEntity user, final String currentPassword,
                                             final String newPassword, final String source, final ObjectId actingApiKeyId) {

        if (user.getPassword() == null) {
            return new ServiceResponse("The user has no password. An administrator sets the first one.", false, 409);
        }
        if (!passwordMatches(user, currentPassword)) {
            return new ServiceResponse("The current password is not correct.", false, 403);
        }
        final String problem = passwordProblem(newPassword);
        if (problem != null) {
            return new ServiceResponse(problem, false, 400);
        }
        if (passwordMatches(user, newPassword)) {
            return new ServiceResponse("The new password must differ from the current one.", false, 400);
        }

        if (!writePassword(user, passwordEncoder.encode(newPassword), false)) {
            return new ServiceResponse("The password was changed by another request. Try again.", false, 409);
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_PASSWORD_CHANGED, user.getId(), user.getId(),
                source, withApiKey(null, actingApiKeyId));

        return ServiceResponse.success("Password changed.");

    }

    /**
     * Sets a user's password without the current one, as an administrator does. Audited as
     * {@code user_password_reset} when it replaces a password and {@code user_password_set} when the user
     * had none, naming {@code actingUserId} and {@code actingApiKeyId}, never the password. The caller
     * revokes the user's session keys.
     *
     * @param changeRequired whether the user must change it at next sign-in.
     * @return a failure with status 400 if the password is not acceptable, or 409 if it changed concurrently.
     */
    public ServiceResponse setPassword(final String requestId, final UserEntity user, final String newPassword,
                                       final boolean changeRequired, final String source,
                                       final ObjectId actingUserId, final ObjectId actingApiKeyId) {

        final String problem = passwordProblem(newPassword);
        if (problem != null) {
            return new ServiceResponse(problem, false, 400);
        }

        final boolean replacing = user.getPassword() != null;
        if (!writePassword(user, passwordEncoder.encode(newPassword), changeRequired)) {
            return new ServiceResponse("The password was changed by another request. Try again.", false, 409);
        }

        auditEventPublisher.auditEvent(requestId,
                replacing ? AuditLogEvent.USER_PASSWORD_RESET : AuditLogEvent.USER_PASSWORD_SET,
                actingUserId == null ? user.getId() : actingUserId, user.getId(), source,
                withApiKey("change_required: " + changeRequired, actingApiKeyId));

        return ServiceResponse.success(replacing ? "Password reset." : "Password set.");

    }

    /**
     * Writes a new hash only if the stored one is still the one this request read, so two concurrent
     * changes cannot both succeed against the same current password.
     */
    private boolean writePassword(final UserEntity user, final String newHash, final boolean changeRequired) {
        final boolean written = collection.updateOne(
                Filters.and(Filters.eq("_id", user.getId()), Filters.eq("password", user.getPassword())),
                new Document("$set", new Document("password", newHash).append("password_change_required", changeRequired)))
                .getMatchedCount() == 1;
        if (written) {
            user.setPassword(newHash);
            user.setPasswordChangeRequired(changeRequired);
        }
        return written;
    }

    private boolean updateFields(final UserEntity user, final String... fields) {
        final Document serialized = user.toDocument(encryptionService);
        final Document selected = new Document();
        for (final String field : fields) selected.put(field, serialized.get(field));
        final Document update = new Document("$set", selected);
        org.bson.conversions.Bson predicate = Filters.eq("_id", user.getId());
        if (selected.containsKey("deactivated")) {
            predicate = Filters.and(predicate, Filters.ne("deactivated", user.isDeactivated()));
        }
        return collection.updateOne(predicate, update).getMatchedCount() == 1;
    }


}
