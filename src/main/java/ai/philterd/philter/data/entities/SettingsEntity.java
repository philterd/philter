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

public class SettingsEntity extends AbstractEntity {

    private ObjectId id;
    private ObjectId userId;
    private boolean redactionLedgerEnabled;

    public static SettingsEntity fromDocument(final Document document) {
        final SettingsEntity settingsEntity = new SettingsEntity();
        settingsEntity.setId(document.getObjectId("_id"));
        settingsEntity.setUserId(document.getObjectId("user_id"));
        settingsEntity.setRedactionLedgerEnabled(document.getBoolean("redaction_ledger_enabled", true));
        return settingsEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("redaction_ledger_enabled", redactionLedgerEnabled);
        return document;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public boolean isRedactionLedgerEnabled() {
        return redactionLedgerEnabled;
    }

    public void setRedactionLedgerEnabled(boolean redactionLedgerEnabled) {
        this.redactionLedgerEnabled = redactionLedgerEnabled;
    }

}
