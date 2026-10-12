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
package ai.philterd.philter.data.sqlite;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.data.services.StoredAdminSettingsDataService;
import ai.philterd.philter.services.encryption.EncryptionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stores the admin settings in SQLite, as one row. */
public class SqliteAdminSettingsDataService extends StoredAdminSettingsDataService {

    private final SqliteDatabase database;

    public SqliteAdminSettingsDataService(final SqliteDatabase database, final EncryptionService encryptionService,
                                          final AuditEventPublisher auditEventPublisher) {
        super(encryptionService, auditEventPublisher);
        this.database = database;
    }

    @Override
    protected StoredSettings read() {
        return database.read(connection -> Sql.first(connection, "SELECT * FROM admin_settings WHERE id = 1", row -> {
            final AdminSettingsEntity settings = new AdminSettingsEntity();
            settings.setDiffuseCountsEnabled(row.bool("diffuse_counts_enabled"));
            settings.setSigningEnabled(row.bool("signing_enabled"));
            settings.setWebhookAllowlist(row.string("webhook_allowlist"));
            settings.setPhieldEnabled(row.bool("phield_enabled"));
            settings.setPhieldUrl(row.string("phield_url"));
            settings.setPhieldSourceId(row.string("phield_source_id"));
            settings.setPhieldOrganization(row.string("phield_organization"));
            settings.setMfaAvailable(row.bool("mfa_available"));
            settings.setMfaRequired(row.bool("mfa_required"));
            final String apiKey = row.string("phield_api_key");
            return new StoredSettings(settings, apiKey == null ? null : new SecretFields.Sealed(apiKey, row.string("phield_api_key_key")));
        }));
    }

    @Override
    protected void write(final Map<Setting, Object> values) {
        final List<String> sets = new ArrayList<>();
        final List<Object> params = new ArrayList<>();
        values.forEach((setting, value) -> {
            if (value instanceof SecretFields.Sealed sealed) {
                sets.add(setting.getName() + " = ?");
                sets.add(setting.getName() + "_key = ?");
                params.add(sealed.ciphertext());
                params.add(sealed.key());
            } else {
                sets.add(setting.getName() + " = ?");
                params.add(value);
            }
        });
        database.write(connection -> {
            Sql.update(connection, "INSERT INTO admin_settings (id) VALUES (1) ON CONFLICT (id) DO NOTHING");
            return Sql.update(connection, "UPDATE admin_settings SET " + String.join(", ", sets) + " WHERE id = 1", params.toArray());
        });
    }

    @Override
    protected boolean isActiveAdministrator(final String userId) {
        return SqliteUsers.isActiveAdministrator(database, userId);
    }

}
