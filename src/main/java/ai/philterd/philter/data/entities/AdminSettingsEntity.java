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
package ai.philterd.philter.data.entities;

import org.bson.Document;
import org.bson.types.ObjectId;

public class AdminSettingsEntity extends AbstractEntity {

    private ObjectId id;
    private boolean loggingEnabled;
    private boolean redactionLedgerOptionEnabled;

    public static AdminSettingsEntity fromDocument(final Document document) {
        final AdminSettingsEntity adminSettingsEntity = new AdminSettingsEntity();
        adminSettingsEntity.setId(document.getObjectId("_id"));
        adminSettingsEntity.setLoggingEnabled(document.getBoolean("logging_enabled", false));
        adminSettingsEntity.setRedactionLedgerOptionEnabled(document.getBoolean("redaction_ledger_option_enabled", true));
        return adminSettingsEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("logging_enabled", loggingEnabled);
        document.put("redaction_ledger_option_enabled", redactionLedgerOptionEnabled);
        return document;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    public void setLoggingEnabled(boolean loggingEnabled) {
        this.loggingEnabled = loggingEnabled;
    }

    public boolean isRedactionLedgerOptionEnabled() {
        return redactionLedgerOptionEnabled;
    }

    public void setRedactionLedgerOptionEnabled(boolean redactionLedgerOptionEnabled) {
        this.redactionLedgerOptionEnabled = redactionLedgerOptionEnabled;
    }

}
