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
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

public class AdminSettingsDataService extends AbstractService<AdminSettingsEntity> {

    public AdminSettingsDataService(final MongoClient mongoClient, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "admin_settings", auditEventPublisher);
    }

    public AdminSettingsEntity findAdminSettings() {

        final Document document = collection.find().first();

        if (document != null) {
            return AdminSettingsEntity.fromDocument(document);
        }

        return null;

    }

    public void saveLoggingEnabled(final boolean loggingEnabled) {
        updateSetting("logging_enabled", loggingEnabled);
    }

    public void saveRedactionLedgerOptionEnabled(final boolean redactionLedgerOptionEnabled) {
        updateSetting("redaction_ledger_option_enabled", redactionLedgerOptionEnabled);
    }

    private void updateSetting(final String key, final Object value) {
        final Bson filter = new Document();
        final Bson update = Updates.set(key, value);
        final UpdateOptions options = new UpdateOptions().upsert(true);
        collection.updateOne(filter, update, options);
    }

}
