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

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import org.bson.Document;

/** Maps {@link AdminSettingsEntity} to and from the documents MongoDB stores. */
public final class AdminSettingsDocuments {

    private AdminSettingsDocuments() {
    }

    public static AdminSettingsEntity fromDocument(final Document document) {
        final AdminSettingsEntity adminSettingsEntity = new AdminSettingsEntity();
        adminSettingsEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        adminSettingsEntity.setDiffuseCountsEnabled(document.getBoolean("diffuse_counts_enabled", false));
        adminSettingsEntity.setPhieldEnabled(document.getBoolean("phield_enabled", false));
        adminSettingsEntity.setPhieldUrl(document.getString("phield_url") != null ? document.getString("phield_url") : "");
        adminSettingsEntity.setPhieldSourceId(document.getString("phield_source_id") != null ? document.getString("phield_source_id") : "philter");
        adminSettingsEntity.setPhieldOrganization(document.getString("phield_organization") != null ? document.getString("phield_organization") : "philter");
        adminSettingsEntity.setSigningEnabled(document.getBoolean("signing_enabled", false));
        adminSettingsEntity.setMfaAvailable(document.getBoolean("mfa_available", false));
        adminSettingsEntity.setMfaRequired(document.getBoolean("mfa_required", false));
        adminSettingsEntity.setWebhookAllowlist(document.getString("webhook_allowlist") == null ? "" : document.getString("webhook_allowlist"));
        return adminSettingsEntity;
    }

    public static Document toDocument(final AdminSettingsEntity entity) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("diffuse_counts_enabled", entity.isDiffuseCountsEnabled());
        document.put("phield_enabled", entity.isPhieldEnabled());
        document.put("phield_url", entity.getPhieldUrl());
        document.put("phield_source_id", entity.getPhieldSourceId());
        document.put("phield_organization", entity.getPhieldOrganization());
        // The Phield API key is deliberately absent: it is encrypted at rest, and AdminSettingsDataService
        // is the only writer of it. Emitting it here would write the decrypted key back in the clear.
        document.put("signing_enabled", entity.isSigningEnabled());
        document.put("mfa_available", entity.isMfaAvailable());
        document.put("mfa_required", entity.isMfaRequired());
        document.put("webhook_allowlist", entity.getWebhookAllowlist());
        return document;
    }

}
