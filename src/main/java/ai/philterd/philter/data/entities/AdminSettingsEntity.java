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
    private boolean diffuseCountsEnabled;
    private boolean phieldEnabled;
    private String phieldUrl = "";
    private String phieldSourceId = "philter";
    private String phieldOrganization = "philter";
    private boolean signingEnabled;

    public static AdminSettingsEntity fromDocument(final Document document) {
        final AdminSettingsEntity adminSettingsEntity = new AdminSettingsEntity();
        adminSettingsEntity.setId(document.getObjectId("_id"));
        adminSettingsEntity.setLoggingEnabled(document.getBoolean("logging_enabled", false));
        adminSettingsEntity.setDiffuseCountsEnabled(document.getBoolean("diffuse_counts_enabled", false));
        adminSettingsEntity.setPhieldEnabled(document.getBoolean("phield_enabled", false));
        adminSettingsEntity.setPhieldUrl(document.getString("phield_url") != null ? document.getString("phield_url") : "");
        adminSettingsEntity.setPhieldSourceId(document.getString("phield_source_id") != null ? document.getString("phield_source_id") : "philter");
        adminSettingsEntity.setPhieldOrganization(document.getString("phield_organization") != null ? document.getString("phield_organization") : "philter");
        adminSettingsEntity.setSigningEnabled(document.getBoolean("signing_enabled", false));
        return adminSettingsEntity;
    }

    @Override
    public Document toDocument() {
        final Document document = new Document();
        if (id != null) {
            document.put("_id", id);
        }
        document.put("logging_enabled", loggingEnabled);
        document.put("diffuse_counts_enabled", diffuseCountsEnabled);
        document.put("phield_enabled", phieldEnabled);
        document.put("phield_url", phieldUrl);
        document.put("phield_source_id", phieldSourceId);
        document.put("phield_organization", phieldOrganization);
        document.put("signing_enabled", signingEnabled);
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

    public boolean isDiffuseCountsEnabled() {
        return diffuseCountsEnabled;
    }

    public void setDiffuseCountsEnabled(boolean diffuseCountsEnabled) {
        this.diffuseCountsEnabled = diffuseCountsEnabled;
    }

    public boolean isPhieldEnabled() {
        return phieldEnabled;
    }

    public void setPhieldEnabled(boolean phieldEnabled) {
        this.phieldEnabled = phieldEnabled;
    }

    public String getPhieldUrl() {
        return phieldUrl;
    }

    public void setPhieldUrl(String phieldUrl) {
        this.phieldUrl = phieldUrl;
    }

    public String getPhieldSourceId() {
        return phieldSourceId;
    }

    public void setPhieldSourceId(String phieldSourceId) {
        this.phieldSourceId = phieldSourceId;
    }

    public String getPhieldOrganization() {
        return phieldOrganization;
    }

    public void setPhieldOrganization(String phieldOrganization) {
        this.phieldOrganization = phieldOrganization;
    }

    public boolean isSigningEnabled() {
        return signingEnabled;
    }

    public void setSigningEnabled(boolean signingEnabled) {
        this.signingEnabled = signingEnabled;
    }

}
