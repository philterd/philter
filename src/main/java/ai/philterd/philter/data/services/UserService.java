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
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class UserService extends AbstractEncryptedService<UserEntity> {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);

    private final PasswordEncoder passwordEncoder;
    private final MongoClient mongoClient;

    public UserService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "users", encryptionService, auditEventPublisher);
        this.mongoClient = mongoClient;
        this.passwordEncoder = new BCryptPasswordEncoder();

        // Users are looked up by email at login.
        ensureIndex(Indexes.ascending("email"));
    }

    public UserEntity findByEmail(final String email) {
        final Document document = collection.find(Filters.eq("email", email)).first();
        if (document != null) {
            return UserEntity.fromDocument(document);
        }
        return null;
    }

    public UserEntity findOneById(final ObjectId id) {

        final Document query = new Document("_id", id);

        final Document document = collection.find(query).first();

        if(document != null) {

            return UserEntity.fromDocument(document);

        } else {

            return null;

        }

    }

    public ServiceResponse createUser(final String requestId, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final String source) {
        return createUser(requestId, email, plainPassword, role, policyService, source, false);
    }

    public ServiceResponse createUser(final String requestId, final String email, final String plainPassword, final String role, final PolicyDataService policyService, final String source, final boolean passwordChangeRequired) {

        if(findByEmail(email) != null) {
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
        policyEntity.setPolicy(SimplifiedPolicy.getDefaultPolicy());
        policyEntity.setCreatedTimestamp(new Date());
        policyEntity.setLastUpdatedTimestamp(new Date());
        policyEntity.setRevision(0);
        policyEntity.setShared(false);
        policyEntity.setManaged(false);
        policyEntity.setDescription("Default policy");
        policyEntity.setNotes("Default policy for new users that can be modified or used as an example.");
        policyService.save(policyEntity);

        return ServiceResponse.success("User created.");

    }

    /** Returns whether the supplied plaintext password matches the user's stored (hashed) password. */
    public boolean passwordMatches(final UserEntity userEntity, final String plainPassword) {
        if (userEntity == null || userEntity.getPassword() == null || plainPassword == null) {
            return false;
        }
        return passwordEncoder.matches(plainPassword, userEntity.getPassword());
    }

    public List<UserEntity> findAll(final int offset, final int limit) {

        final FindIterable<Document> documents = collection.find().sort(Sorts.ascending("email")).skip(offset).limit(limit);

        final List<UserEntity> userEntities = new ArrayList<>();

        for (final Document document : documents) {
            userEntities.add(UserEntity.fromDocument(document));
        }

        return userEntities;

    }

    public int count() {
        return (int) collection.countDocuments();
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

    public void deleteUser(final String requestId, final UserEntity userEntity, final String source) {

        // Capture identity before deletion for the audit record.
        final ObjectId deletedUserId = userEntity.getId();

        final MongoDatabase philterDatabase = mongoClient.getDatabase("philter");

        // Delete from api_keys (mark as deleted)
        // Note: we'll just set deleted to true for consistency with ApiKeyDataService.deleteByApiKey
        philterDatabase.getCollection("api_keys").updateMany(
                Filters.eq("user_id", userEntity.getId()),
                new Document("$set", new Document("deleted", true))
        );

        // Contexts are shared across users, so they (along with their context_entries and span
        // disambiguation vectors) are intentionally NOT deleted when a user is removed.

        // Delete from custom_lists
        philterDatabase.getCollection("custom_lists").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from policies
        philterDatabase.getCollection("policies").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from ledger
        philterDatabase.getCollection("ledger").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from redaction_ledger (if it exists)
        philterDatabase.getCollection("redaction_ledger").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete the user
        collection.deleteOne(Filters.eq("_id", userEntity.getId()));

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.USER_DELETED, deletedUserId, deletedUserId, source, null);

    }

}
