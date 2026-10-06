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

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The deployment's admin settings. Says whether a Phield API key is set and never carries it. Also reports,
 * read-only, the environment variables that decide which features a client should offer.
 */
public class SettingsResponse {

    private final boolean diffuseCountsEnabled;
    private final boolean signingEnabled;
    private final String webhookAllowlist;
    private final boolean phieldEnabled;
    private final String phieldUrl;
    private final String phieldSourceId;
    private final String phieldOrganization;
    private final boolean phieldApiKeySet;
    private final boolean mfaAvailable;
    private final boolean mfaRequired;
    private final List<String> warnings;
    private final boolean crossUserAccessEnabled;
    private final boolean ledgerDeletionEnabled;
    private final boolean signingKeyExternallyManaged;

    /** The stored settings, or the defaults when none have been saved, and the deployment's flags. */
    public SettingsResponse(final AdminSettingsEntity settings, final List<String> warnings,
                            final boolean crossUserAccessEnabled, final boolean ledgerDeletionEnabled,
                            final boolean signingKeyExternallyManaged) {
        final AdminSettingsEntity s = settings == null ? new AdminSettingsEntity() : settings;
        this.diffuseCountsEnabled = s.isDiffuseCountsEnabled();
        this.signingEnabled = s.isSigningEnabled();
        this.webhookAllowlist = s.getWebhookAllowlist();
        this.phieldEnabled = s.isPhieldEnabled();
        this.phieldUrl = s.getPhieldUrl();
        this.phieldSourceId = s.getPhieldSourceId();
        this.phieldOrganization = s.getPhieldOrganization();
        this.phieldApiKeySet = s.getPhieldApiKey() != null && !s.getPhieldApiKey().isEmpty();
        this.mfaAvailable = s.isMfaAvailable();
        this.mfaRequired = s.isMfaRequired();
        this.warnings = warnings;
        this.crossUserAccessEnabled = crossUserAccessEnabled;
        this.ledgerDeletionEnabled = ledgerDeletionEnabled;
        this.signingKeyExternallyManaged = signingKeyExternallyManaged;
    }

    public boolean isDiffuseCountsEnabled() { return diffuseCountsEnabled; }

    public boolean isSigningEnabled() { return signingEnabled; }

    public String getWebhookAllowlist() { return webhookAllowlist; }

    public boolean isPhieldEnabled() { return phieldEnabled; }

    public String getPhieldUrl() { return phieldUrl; }

    public String getPhieldSourceId() { return phieldSourceId; }

    public String getPhieldOrganization() { return phieldOrganization; }

    public boolean isPhieldApiKeySet() { return phieldApiKeySet; }

    public boolean isMfaAvailable() { return mfaAvailable; }

    public boolean isMfaRequired() { return mfaRequired; }

    /** Warnings about the saved settings, such as a Phield API key that will be sent over http. Empty on a read. */
    public List<String> getWarnings() { return warnings; }

    @Schema(description = "Whether administrators may use owner and all_users to reach other users' resources. "
            + "Set by ADMIN_CROSS_USER_ACCESS_ENABLED; read-only, and ignored by PATCH.", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isCrossUserAccessEnabled() { return crossUserAccessEnabled; }

    @Schema(description = "Whether administrators may delete and purge ledger chains. Set by LEDGER_DELETION_ENABLED; "
            + "read-only, and ignored by PATCH.", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isLedgerDeletionEnabled() { return ledgerDeletionEnabled; }

    @Schema(description = "Whether the output signing key comes from PHILTER_SIGNING_KEY_PATH, in which case it cannot "
            + "be rotated through the API. Read-only, and ignored by PATCH.", accessMode = Schema.AccessMode.READ_ONLY)
    public boolean isSigningKeyExternallyManaged() { return signingKeyExternallyManaged; }

}
