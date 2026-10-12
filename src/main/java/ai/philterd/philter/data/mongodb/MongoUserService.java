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
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StoredUserService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stores users in MongoDB, one document per user, kept when the user is deactivated. */
public class MongoUserService extends StoredUserService {

    private final EncryptionService encryptionService;
    private final MongoRecords<UserEntity> records;
    private final MongoCollection<Document> collection;
    private final MongoAdministratorGuard administratorGuard;

    public MongoUserService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(auditEventPublisher);
        this.encryptionService = encryptionService;
        this.records = new MongoRecords<>(mongoClient, "users", entity -> UserDocuments.toDocument(entity, encryptionService));
        this.collection = records.collection;
        this.administratorGuard = new MongoAdministratorGuard(mongoClient);
        records.ensureIndex(Indexes.ascending("username"), new IndexOptions().unique(true));
    }

    private static Bson byId(final String userId) {
        return Filters.eq("_id", MongoIds.objectId(userId));
    }

    @Override
    protected String insert(final UserEntity user) {
        return records.insert(user);
    }

    /** The stored fields each account change writes. */
    private static List<String> fields(final Field field) {
        return switch (field) {
            case ROLE -> List.of("role");
            case EMAIL -> List.of("username", "email");
            case DEACTIVATION -> List.of("deactivated", "deactivated_at");
            case WEBHOOK -> List.of("webhook_url", "webhook_secret", "webhook_secret_key");
            case MFA_PENDING_SECRET -> List.of("mfa_pending_secret", "mfa_pending_secret_key");
            case MFA -> List.of("mfa_enabled", "mfa_secret", "mfa_secret_key", "mfa_pending_secret", "mfa_pending_secret_key",
                    "mfa_failed_attempts", "mfa_locked", "mfa_last_used_time_step");
        };
    }

    private Document selected(final UserEntity user, final Field field) {
        final Document serialized = UserDocuments.toDocument(user, encryptionService);
        final Document selected = new Document();
        for (final String name : fields(field)) {
            selected.put(name, serialized.get(name));
        }
        return selected;
    }

    @Override
    protected boolean write(final UserEntity user, final Field field) {
        Bson predicate = byId(user.getId());
        if (field == Field.DEACTIVATION) {
            predicate = Filters.and(predicate, Filters.ne("deactivated", user.isDeactivated()));
        }
        return collection.updateOne(predicate, new Document("$set", selected(user, field))).getMatchedCount() == 1;
    }

    private static Bson activeAdmin() {
        return Filters.and(Filters.regex("role", "^" + ROLE_ADMIN + "$", "i"), Filters.ne("deactivated", true));
    }

    @Override
    protected AdministratorChange writeKeepingAnAdministrator(final UserEntity user, final Field field) {
        // Every change that can remove an administrator holds the guard from its count to its write.
        return administratorGuard.execute(() -> {
            final boolean activeAdmin = collection.countDocuments(Filters.and(byId(user.getId()), activeAdmin())) == 1;
            if (activeAdmin && collection.countDocuments(Filters.and(Filters.ne("_id", MongoIds.objectId(user.getId())),
                    activeAdmin())) == 0) {
                return AdministratorChange.LAST_ADMINISTRATOR;
            }
            return write(user, field) ? AdministratorChange.WRITTEN : AdministratorChange.UNCHANGED;
        });
    }

    @Override
    protected boolean enrollMfa(final UserEntity user) {
        return collection.updateOne(Filters.and(byId(user.getId()), Filters.ne("mfa_enabled", true)),
                new Document("$set", selected(user, Field.MFA))).getMatchedCount() == 1;
    }

    @Override
    protected boolean acceptMfaStep(final String userId, final long step) {
        return collection.updateOne(Filters.and(byId(userId),
                        Filters.eq("mfa_enabled", true), Filters.ne("mfa_locked", true),
                        Filters.or(Filters.lt("mfa_last_used_time_step", step),
                                Filters.exists("mfa_last_used_time_step", false))),
                new Document("$set", new Document("mfa_last_used_time_step", step).append("mfa_failed_attempts", 0)))
                .getMatchedCount() == 1;
    }

    @Override
    protected int countMfaFailure(final String userId) {
        final Document after = collection.findOneAndUpdate(Filters.and(byId(userId), Filters.eq("mfa_enabled", true)),
                new Document("$inc", new Document("mfa_failed_attempts", 1)),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
        return after == null ? 0 : after.getInteger("mfa_failed_attempts", 0);
    }

    @Override
    protected boolean lockMfa(final String userId) {
        return collection.updateOne(Filters.and(byId(userId), Filters.ne("mfa_locked", true)),
                new Document("$set", new Document("mfa_locked", true))).getMatchedCount() == 1;
    }

    @Override
    protected boolean unlockMfa(final String userId) {
        return collection.updateOne(Filters.and(byId(userId), Filters.eq("mfa_locked", true)),
                new Document("$set", new Document("mfa_locked", false).append("mfa_failed_attempts", 0)))
                .getMatchedCount() == 1;
    }

    @Override
    protected boolean writePassword(final String userId, final String expectedHash, final String newHash,
                                    final boolean changeRequired) {
        return collection.updateOne(Filters.and(byId(userId), Filters.eq("password", expectedHash)),
                new Document("$set", new Document("password", newHash).append("password_change_required", changeRequired)))
                .getMatchedCount() == 1;
    }

    private UserEntity first(final Bson filter) {
        final Document document = collection.find(filter).first();
        return document == null ? null : UserDocuments.fromDocument(document, encryptionService);
    }

    @Override
    public UserEntity findByUsername(final String username) {
        return first(Filters.and(Filters.eq("username", username), Filters.ne("deactivated", true)));
    }

    @Override
    public UserEntity findAnyByUsername(final String username) {
        return first(Filters.eq("username", username));
    }

    @Override
    public UserEntity findOneById(final String id) {
        return first(byId(id));
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
    public Listings.Page<UserEntity> list(final String q, final String role, final Boolean active,
                                          final Listings.Sort sort, final int offset, final int limit) {
        return MongoListings.page(collection, MongoListings.all(MongoListings.contains("username", q),
                        role == null ? null : Filters.eq("role", role),
                        active == null ? null : active ? Filters.ne("deactivated", true) : Filters.eq("deactivated", true)),
                sort, offset, limit, document -> UserDocuments.fromDocument(document, encryptionService));
    }

    @Override
    public int count(final boolean includeDeactivated) {
        return (int) (includeDeactivated
                ? collection.countDocuments()
                : collection.countDocuments(Filters.ne("deactivated", true)));
    }

    @Override
    public long countActiveAdmins() {
        return collection.countDocuments(activeAdmin());
    }

}
