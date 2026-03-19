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

import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.DataInitializer;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.policies.SimplifiedPolicy;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
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
    }

    public UserEntity findByEmail(final String email) {
        final Document document = collection.find(Filters.eq("email", email)).first();
        if (document != null) {
            return UserEntity.fromDocument(document);
        }
        return null;
    }

    public ServiceResponse createUser(final String email, final String plainPassword, final String role, final ContextDataService contextService, final PolicyDataService policyService) {

        if(findByEmail(email) != null) {
            return ServiceResponse.failure("User already exists.");
        }

        final UserEntity userEntity = new UserEntity();
        userEntity.setEmail(email);
        userEntity.setPassword(passwordEncoder.encode(plainPassword));
        userEntity.setRole(role);
        final ObjectId userId = save(userEntity);

        // Create the default context.
        LOGGER.info("Inserting default context");
        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.setUserId(userId);
        contextEntity.setContextName("default");
        contextService.save(contextEntity);

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

    public PasswordEncoder getPasswordEncoder() {
        return passwordEncoder;
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

    public void deleteUser(final UserEntity userEntity) {

        final MongoDatabase philterDatabase = mongoClient.getDatabase("philter");
        final MongoDatabase philterdDataServicesDatabase = mongoClient.getDatabase("philterd_data_services");

        // Delete from api_keys (mark as deleted)
        // Note: we'll just set deleted to true for consistency with ApiKeyDataService.deleteByApiKey
        philterDatabase.getCollection("api_keys").updateMany(
                Filters.eq("user_id", userEntity.getId()),
                new Document("$set", new Document("deleted", true))
        );

        // Delete from contexts
        philterDatabase.getCollection("contexts").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from context_entries
        philterDatabase.getCollection("context_entries").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from custom_lists
        philterdDataServicesDatabase.getCollection("custom_lists").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from policies
        philterDatabase.getCollection("policies").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from ledger
        philterdDataServicesDatabase.getCollection("ledger").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete from redaction_ledger (if it exists)
        philterdDataServicesDatabase.getCollection("redaction_ledger").deleteMany(Filters.eq("user_id", userEntity.getId()));

        // Delete the user
        collection.deleteOne(Filters.eq("_id", userEntity.getId()));

    }

}
