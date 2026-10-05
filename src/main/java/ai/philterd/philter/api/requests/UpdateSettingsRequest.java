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
package ai.philterd.philter.api.requests;

/**
 * Request body for {@code PATCH /api/settings}. A field left out is left unchanged. Sending an empty
 * {@code phieldApiKey} removes the key.
 */
public class UpdateSettingsRequest {

    private Boolean diffuseCountsEnabled;
    private Boolean signingEnabled;
    private String webhookAllowlist;
    private Boolean phieldEnabled;
    private String phieldUrl;
    private String phieldSourceId;
    private String phieldOrganization;
    private String phieldApiKey;

    public Boolean getDiffuseCountsEnabled() { return diffuseCountsEnabled; }
    public void setDiffuseCountsEnabled(final Boolean value) { this.diffuseCountsEnabled = value; }

    public Boolean getSigningEnabled() { return signingEnabled; }
    public void setSigningEnabled(final Boolean value) { this.signingEnabled = value; }

    public String getWebhookAllowlist() { return webhookAllowlist; }
    public void setWebhookAllowlist(final String value) { this.webhookAllowlist = value; }

    public Boolean getPhieldEnabled() { return phieldEnabled; }
    public void setPhieldEnabled(final Boolean value) { this.phieldEnabled = value; }

    public String getPhieldUrl() { return phieldUrl; }
    public void setPhieldUrl(final String value) { this.phieldUrl = value; }

    public String getPhieldSourceId() { return phieldSourceId; }
    public void setPhieldSourceId(final String value) { this.phieldSourceId = value; }

    public String getPhieldOrganization() { return phieldOrganization; }
    public void setPhieldOrganization(final String value) { this.phieldOrganization = value; }

    public String getPhieldApiKey() { return phieldApiKey; }
    public void setPhieldApiKey(final String value) { this.phieldApiKey = value; }

}
