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

import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;

public class UserEntity extends AbstractEncryptedEntity {

    private ObjectId id;
    private String email;
    private String password;
    private String role;
    private String fpeKey;
    private boolean changesetsEnabled;

    public static UserEntity fromDocument(final Document document) {
        final UserEntity userEntity = new UserEntity();
        userEntity.setId(document.getObjectId("_id"));
        userEntity.setEmail(document.getString("email"));
        userEntity.setPassword(document.getString("password"));
        userEntity.setRole(document.getString("role"));
        userEntity.setFpeKey(document.getString("fpe_key"));
        userEntity.setChangesetsEnabled(document.getBoolean("changesets_enabled", false));
        return userEntity;
    }

    @Override
    public Document toDocument(final EncryptionService encryptionService) {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("email", email);
        document.put("password", password);
        document.put("role", role);
        document.put("fpe_key", fpeKey);
        document.put("changesets_enabled", changesetsEnabled);
        return document;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getFpeKey() {
        return fpeKey;
    }

    public void setFpeKey(String fpeKey) {
        this.fpeKey = fpeKey;
    }

    public boolean isChangesetsEnabled() {
        return changesetsEnabled;
    }

    public void setChangesetsEnabled(boolean changesetsEnabled) {
        this.changesetsEnabled = changesetsEnabled;
    }
}
