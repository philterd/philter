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
import ai.philterd.philter.data.entities.SigningKeyEntity;
import ai.philterd.philter.data.services.StoredSigningKeyDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;

import java.util.Date;

/** Stores signing keys in MongoDB, with the active key named by a pointer document. */
public class MongoSigningKeyDataService extends StoredSigningKeyDataService {

    private final MongoClient mongoClient;
    private final EncryptionService encryptionService;
    private final MongoCollection<Document> keys;
    private final MongoCollection<Document> keyState;

    public MongoSigningKeyDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                 final AuditEventPublisher auditEventPublisher) {
        this(mongoClient, encryptionService, auditEventPublisher, System.getenv("PHILTER_SIGNING_KEY_PATH"));
    }

    MongoSigningKeyDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                          final AuditEventPublisher auditEventPublisher, final String keyPath) {
        super(auditEventPublisher, keyPath);
        this.mongoClient = mongoClient;
        this.encryptionService = encryptionService;
        keys = mongoClient.getDatabase("philter").getCollection("signing_keys")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        keyState = mongoClient.getDatabase("philter").getCollection("signing_key_state")
                .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY);
        keys.createIndex(Indexes.ascending("key_id"), new IndexOptions().unique(true));
        start();
    }

    @Override
    protected void insertKey(final SigningKeyEntity key) {
        keys.insertOne(SigningKeyDocuments.toDocument(key, encryptionService));
    }

    @Override
    protected void retainPublicKey(final String keyId, final byte[] publicKeyEncoded) {
        keys.updateOne(Filters.eq("key_id", keyId),
                new Document("$setOnInsert", new Document("key_id", keyId)
                        .append("public_key", publicKeyEncoded).append("created_at", new Date())),
                new UpdateOptions().upsert(true));
    }

    @Override
    protected SigningKeyEntity findKey(final String keyId) {
        final Document document = keys.find(Filters.eq("key_id", keyId)).first();
        return document == null ? null : SigningKeyDocuments.fromDocument(document, encryptionService);
    }

    @Override
    protected byte[] findPublicKey(final String keyId) {
        final Document document = keys.find(Filters.eq("key_id", keyId)).first();
        return document == null ? null : SigningKeyDocuments.publicPartFromDocument(document).getPublicKeyEncoded();
    }

    @Override
    protected String findActiveKeyId() {
        final Document pointer = keyState.find(Filters.eq("_id", "active")).first();
        return pointer == null ? null : pointer.getString("key_id");
    }

    @Override
    protected boolean publishFirstActiveKey(final String keyId) {
        return keyState.updateOne(Filters.eq("_id", "active"),
                new Document("$setOnInsert", new Document("key_id", keyId)),
                new UpdateOptions().upsert(true)).getUpsertedId() != null;
    }

    @Override
    protected boolean publishActiveKey(final String keyId) {
        return keyState.updateOne(Filters.eq("_id", "active"), Updates.set("key_id", keyId)).getMatchedCount() == 1;
    }

    @Override
    protected boolean isActiveAdministrator(final String userId) {
        return AdministratorAuthorization.isActiveAdministrator(mongoClient, userId);
    }

}
