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

public class UserService extends AbstractEncryptedService<UserEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    private final PasswordEncoder passwordEncoder;

    public UserService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "users", encryptionService, auditEventPublisher);
        this.passwordEncoder = new BCryptPasswordEncoder();

        // Users are looked up by email at login.
        ensureIndex(Indexes.ascending("email"));
    }

    /**
     * Looks up an <strong>active</strong> (not deactivated) user by email. Deactivated users are
     * excluded so they cannot sign in and cannot be targeted via the cross-user {@code owner}
     * parameter. Use {@link #findOneById(ObjectId)} or {@link #findEmailsByIds(Collection)} to resolve
     * a deactivated user for audit and ledger display, and {@link #findAnyByEmail(String)} to detect an
     * email that is already taken (including by a deactivated account).
     */
    public UserEntity findByEmail(final String email) {
        final Document document = collection.find(
                Filters.and(Filters.eq("email", email), Filters.ne("deactivated", true))).first();
        if (document != null) {
            return UserEntity.fromDocument(document);
        }
        return null;
    }

    /**
     * Looks up a user by email regardless of deactivation state. Used when creating an account to
     * reject an email that already belongs to any user, active or deactivated: a deactivated account
     * keeps its email reserved so it can be reactivated rather than duplicated.
     */
    public UserEntity findAnyByEmail(final String email) {
        final Document document = collection.find(Filters.eq("email", email)).first();
        if (document != null) {
            return UserEntity.fromDocument(document);
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

            return UserEntity.fromDocument(document);

        } else {

            return null;

        }

    }

    /**
     * Resolves several user ids to their email addresses in a single query, returning a map of id to
     * email. Used by the admin "All ..." views to label each row with its owner without issuing one
     * lookup per row. Ids with no matching user are simply absent from the returned map.
     */
    public Map<ObjectId, String> findEmailsByIds(final Collection<ObjectId> ids) {

        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<ObjectId, String> emailsById = new HashMap<>();
        for (final Document document : collection.find(Filters.in("_id", ids))) {
            final UserEntity user = UserEntity.fromDocument(document);
            emailsById.put(user.getId(), user.getEmail());
        }

        return emailsById;

    }

    public ServiceResponse createUser(final String requestId, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source) {
        return createUser(requestId, email, plainPassword, role, policyService, contextService, source, false);
    }

    public ServiceResponse createUser(final String requestId, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final ContextDataService contextService, final String source, final boolean passwordChangeRequired) {

        final UserEntity existing = findAnyByEmail(email);
        if(existing != null) {
            // An email belonging to a deactivated account stays reserved: reactivate it rather than
            // creating a duplicate user with the same email.
            if (existing.isDeactivated()) {
                return ServiceResponse.failure("A deactivated user already exists with that email. Reactivate that user instead.");
            }
            return ServiceResponse.failure("User already exists.");
        }

        final UserEntity userEntity = new UserEntity();
        userEntity.setEmail(email);
        userEntity.setPassword(passwordEncoder.encode(plainPassword));
        userEntity.setRole(role);
        userEntity.setPasswordChangeRequired(passwordChangeRequired);
        // A stable per-user key for the FPE_ENCRYPT_REPLACE strategy. It is generated once and never
        // changes so format-preserving encryption is deterministic for the user.
        userEntity.setFpeKey(EncryptionService.generateFpeKey());
        final ObjectId userId = save(userEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_CREATED, userId, userId, source, "role: " + role);

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
                .sort(Sorts.ascending("email")).skip(offset).limit(limit);

        final List<UserEntity> userEntities = new ArrayList<>();

        for (final Document document : documents) {
            userEntities.add(UserEntity.fromDocument(document));
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

        if(userEntity == null) {

            return ServiceResponse.failure("User does not exist.");

        } else {

            userEntity.setPassword(passwordEncoder.encode(newPassword));
            // Changing the password satisfies any forced-reset requirement.
            userEntity.setPasswordChangeRequired(false);
            update(userEntity);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_PASSWORD_CHANGED, userEntity.getId(), userEntity.getId(), source, null);

            return ServiceResponse.success("Password changed.");

        }

    }

    public ServiceResponse setUserRole(final String requestId, final UserEntity userEntity, final String newRole, final String source) {

        if (userEntity == null) {
            return ServiceResponse.failure("User does not exist.");

        } else {
            userEntity.setRole(newRole);
            update(userEntity);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_ROLE_CHANGED, userEntity.getId(), userEntity.getId(), source, "role: " + newRole);

            return ServiceResponse.success("User role updated.");
        }

    }

    /**
     * Returns the user's FPE key, generating and persisting one if it is missing. This lazily backfills
     * users created before per-user FPE keys existed, guaranteeing the {@code FPE_ENCRYPT_REPLACE}
     * strategy always has a usable key. The key is assigned once and then stable, so format-preserving
     * encryption stays deterministic for the user across requests.
     */
    public String ensureFpeKey(final UserEntity userEntity) {

        String fpeKey = userEntity.getFpeKey();

        if (fpeKey == null || fpeKey.isBlank()) {
            fpeKey = EncryptionService.generateFpeKey();
            userEntity.setFpeKey(fpeKey);
            update(userEntity);
            LOGGER.info("Backfilled a missing FPE key for user {}.", userEntity.getId());
        }

        return fpeKey;

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

        if (userEntity.isDeactivated()) {
            return;
        }

        userEntity.setDeactivated(true);
        userEntity.setDeactivatedAt(new Date());
        update(userEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_DEACTIVATED, userEntity.getId(), userEntity.getId(), source,
                "account deactivated; user data retained, including policies and redaction ledger (evidence preserved)");

    }

    /**
     * Reactivates a previously deactivated user, restoring sign-in and API access. The user's data was
     * never removed on deactivation, so reactivation returns the account to exactly its prior state.
     */
    public void reactivateUser(final String requestId, final UserEntity userEntity, final String source) {

        if (!userEntity.isDeactivated()) {
            return;
        }

        userEntity.setDeactivated(false);
        userEntity.setDeactivatedAt(null);
        update(userEntity);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_REACTIVATED, userEntity.getId(), userEntity.getId(), source, null);

    }

}
