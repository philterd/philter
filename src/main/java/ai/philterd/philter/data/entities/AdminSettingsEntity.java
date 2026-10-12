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

public class AdminSettingsEntity extends AbstractEntity {

    private String id;
    private boolean diffuseCountsEnabled;
    private boolean phieldEnabled;
    private String phieldUrl = "";
    private String phieldSourceId = "philter";
    private String phieldOrganization = "philter";
    private String phieldApiKey = "";
    private boolean signingEnabled;
    // MFA: whether users may enroll, and whether every user who signs in must.
    private boolean mfaAvailable;
    private boolean mfaRequired;
    // Hosts and IP/CIDR entries a user's webhook URL may point at. Empty means any public address.
    private String webhookAllowlist = "";

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getPhieldApiKey() {
        return phieldApiKey;
    }

    public void setPhieldApiKey(String phieldApiKey) {
        this.phieldApiKey = phieldApiKey;
    }

    public String getWebhookAllowlist() {
        return webhookAllowlist;
    }

    public void setWebhookAllowlist(final String webhookAllowlist) {
        this.webhookAllowlist = webhookAllowlist == null ? "" : webhookAllowlist;
    }

    public boolean isSigningEnabled() {
        return signingEnabled;
    }

    public void setSigningEnabled(boolean signingEnabled) {
        this.signingEnabled = signingEnabled;
    }

    public boolean isMfaAvailable() {
        return mfaAvailable;
    }

    public void setMfaAvailable(final boolean mfaAvailable) {
        this.mfaAvailable = mfaAvailable;
    }

    public boolean isMfaRequired() {
        return mfaRequired;
    }

    public void setMfaRequired(final boolean mfaRequired) {
        this.mfaRequired = mfaRequired;
    }

}
