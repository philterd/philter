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
package ai.philterd.philter.api.responses;

import ai.philterd.philter.data.entities.AdminSettingsEntity;

import java.util.List;

/** The deployment's admin settings. Says whether a Phield API key is set and never carries it. */
public class SettingsResponse {

    private final boolean diffuseCountsEnabled;
    private final boolean signingEnabled;
    private final String webhookAllowlist;
    private final boolean phieldEnabled;
    private final String phieldUrl;
    private final String phieldSourceId;
    private final String phieldOrganization;
    private final boolean phieldApiKeySet;
    private final List<String> warnings;

    /** From the stored settings, or the defaults when none have been saved. */
    public SettingsResponse(final AdminSettingsEntity settings, final List<String> warnings) {
        final AdminSettingsEntity s = settings == null ? new AdminSettingsEntity() : settings;
        this.diffuseCountsEnabled = s.isDiffuseCountsEnabled();
        this.signingEnabled = s.isSigningEnabled();
        this.webhookAllowlist = s.getWebhookAllowlist();
        this.phieldEnabled = s.isPhieldEnabled();
        this.phieldUrl = s.getPhieldUrl();
        this.phieldSourceId = s.getPhieldSourceId();
        this.phieldOrganization = s.getPhieldOrganization();
        this.phieldApiKeySet = s.getPhieldApiKey() != null && !s.getPhieldApiKey().isEmpty();
        this.warnings = warnings;
    }

    public boolean isDiffuseCountsEnabled() { return diffuseCountsEnabled; }

    public boolean isSigningEnabled() { return signingEnabled; }

    public String getWebhookAllowlist() { return webhookAllowlist; }

    public boolean isPhieldEnabled() { return phieldEnabled; }

    public String getPhieldUrl() { return phieldUrl; }

    public String getPhieldSourceId() { return phieldSourceId; }

    public String getPhieldOrganization() { return phieldOrganization; }

    public boolean isPhieldApiKeySet() { return phieldApiKeySet; }

    /** Warnings about the saved settings, such as a Phield API key that will be sent over http. Empty on a read. */
    public List<String> getWarnings() { return warnings; }

}
