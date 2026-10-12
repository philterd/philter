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
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredAdminSettingsDataService;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.util.Map;

/** Stores the admin settings in MongoDB, as one document. */
public class MongoAdminSettingsDataService extends StoredAdminSettingsDataService {

    private final MongoClient mongoClient;
    private final MongoCollection<Document> collection;

    public MongoAdminSettingsDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                    final AuditEventPublisher auditEventPublisher) {
        super(encryptionService, auditEventPublisher);
        this.mongoClient = mongoClient;
        this.collection = mongoClient.getDatabase("philter").getCollection("admin_settings");
    }

    @Override
    protected StoredSettings read() {
        final Document document = collection.find().first();
        if (document == null) {
            return null;
        }
        final String apiKey = document.getString("phield_api_key");
        return new StoredSettings(AdminSettingsDocuments.fromDocument(document),
                apiKey == null ? null : new SecretFields.Sealed(apiKey, document.getString("phield_api_key_key")));
    }

    @Override
    protected void write(final Map<Setting, Object> values) {
        final Document set = new Document();
        values.forEach((setting, value) -> {
            if (value instanceof SecretFields.Sealed sealed) {
                set.append(setting.getName(), sealed.ciphertext()).append(setting.getName() + "_key", sealed.key());
            } else {
                set.append(setting.getName(), value);
            }
        });
        collection.updateOne(new Document(), new Document("$set", set), new UpdateOptions().upsert(true));
    }

    @Override
    protected boolean isActiveAdministrator(final String userId) {
        return AdministratorAuthorization.isActiveAdministrator(mongoClient, userId);
    }

}
