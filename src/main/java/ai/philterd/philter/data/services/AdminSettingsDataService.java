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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.utils.EnvUtils;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.RequestIdGenerator;
import java.util.Objects;
import org.bson.types.ObjectId;

public class AdminSettingsDataService extends AbstractService<AdminSettingsEntity> {

    private final MongoClient mongoClient;

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminSettingsDataService.class);

    /** The Phield API key belongs to the instance, not a user; the key provider ignores this value. */
    private static final String SYSTEM_OWNER = "system";

    private final EncryptionService encryptionService;

    /**
     * One row, read on every redaction and changed rarely. Writes here evict; the TTL bounds how long
     * another instance's write takes to be seen. Callers treat the entity as read-only.
     */
    private static final long CACHE_TTL_MILLIS = EnvUtils.getInt("ADMIN_SETTINGS_CACHE_TTL_SECONDS", 60) * 1000L;

    private volatile AdminSettingsEntity cached;
    private volatile long cachedAt;
    private final java.util.concurrent.atomic.AtomicLong writes = new java.util.concurrent.atomic.AtomicLong();

    public AdminSettingsDataService(final MongoClient mongoClient, final EncryptionService encryptionService,
                                    final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "admin_settings", auditEventPublisher);
        this.mongoClient = mongoClient;
        this.encryptionService = encryptionService;
    }

    public AdminSettingsEntity findAdminSettings() {

        final long now = System.currentTimeMillis();

        if (now - cachedAt < CACHE_TTL_MILLIS) {
            return cached;
        }

        final long seen = writes.get();
        final Document document = collection.find().first();

        AdminSettingsEntity adminSettingsEntity = null;
        if (document != null) {
            adminSettingsEntity = AdminSettingsEntity.fromDocument(document);
            adminSettingsEntity.setPhieldApiKey(decryptPhieldApiKey(document));
        }

        // Not if a write landed mid-read; that would cache the pre-write state for a full TTL.
        if (writes.get() == seen) {
            cached = adminSettingsEntity;
            cachedAt = now;
        }

        return adminSettingsEntity;

    }

    /**
     * Decrypts the stored Phield API key. A value with no accompanying data key was set directly
     * rather than through the dashboard and is returned as-is. A value that cannot be decrypted is
     * reported as unset rather than thrown: every read of the admin settings comes through here, so
     * failing would take output signing and the dashboard down with it, and the Admin page is where
     * the key would be re-entered.
     */
    private String decryptPhieldApiKey(final Document document) {

        final String value = document.getString("phield_api_key");

        if (value == null || value.isEmpty()) {
            return "";
        }

        final String dataKey = document.getString("phield_api_key_key");

        if (dataKey == null || dataKey.isEmpty()) {
            return value;
        }

        try {
            return encryptionService.decrypt(value, dataKey);
        } catch (final Exception ex) {
            LOGGER.warn("Unable to decrypt the stored Phield API key; treating it as unset. Re-enter it on "
                    + "the Admin page if the Phield instance requires one. Cause: {}", ex.getMessage());
            return "";
        }

    }

    /**
     * Encrypts the Phield API key for storage, returning the ciphertext and its wrapped data key as
     * the two fields to write. A blank key is stored as empty and read back as "".
     */
    private Document encryptPhieldApiKey(final String apiKey) {

        if (apiKey == null || apiKey.isBlank()) {
            return new Document("phield_api_key", "").append("phield_api_key_key", "");
        }

        final EncryptResult result = encryptionService.encrypt(apiKey.trim(), SYSTEM_OWNER);

        return new Document("phield_api_key", result.getEncryptedText())
                .append("phield_api_key_key", result.getEncryptionKey());

    }

    public void saveDiffuseCountsEnabled(final ObjectId actingUserId, final boolean diffuseCountsEnabled) {
        ai.philterd.philter.api.security.DashboardAuthorization.requireAdministrator(mongoClient, actingUserId);
        auditChanged(actingUserId, updateSetting("diffuse_counts_enabled", diffuseCountsEnabled));
    }

    public void saveSigningEnabled(final ObjectId actingUserId, final boolean signingEnabled) {
        ai.philterd.philter.api.security.DashboardAuthorization.requireAdministrator(mongoClient, actingUserId);
        auditChanged(actingUserId, updateSetting("signing_enabled", signingEnabled));
    }

    public void saveWebhookAllowlist(final ObjectId actingUserId, final String webhookAllowlist) {
        ai.philterd.philter.api.security.DashboardAuthorization.requireAdministrator(mongoClient, actingUserId);
        auditChanged(actingUserId,
                updateSetting("webhook_allowlist", webhookAllowlist == null ? "" : webhookAllowlist.trim()));
    }

    public void saveMfaEnabled(final ObjectId actingUserId, final boolean mfaEnabled) {
        ai.philterd.philter.api.security.DashboardAuthorization.requireAdministrator(mongoClient, actingUserId);
        auditChanged(actingUserId, updateSetting("mfa_enabled", mfaEnabled));
    }

    public void savePhieldSettings(final ObjectId actingUserId, final boolean enabled, final String url,
                                   final String sourceId, final String organization, final String apiKey) {
        ai.philterd.philter.api.security.DashboardAuthorization.requireAdministrator(mongoClient, actingUserId);

        final List<String> changed = new ArrayList<>();

        changed.addAll(updateSetting("phield_enabled", enabled));
        changed.addAll(updateSetting("phield_url", url != null ? url.trim() : ""));
        changed.addAll(updateSetting("phield_source_id",
                sourceId != null && !sourceId.isBlank() ? sourceId.trim() : "philter"));
        changed.addAll(updateSetting("phield_organization",
                organization != null && !organization.isBlank() ? organization.trim() : "philter"));

        // Encrypted at rest. The ciphertext and its wrapped data key are one logical value, so they are
        // written in a single update: a partial write would leave a key that cannot be decrypted.
        changed.addAll(updateSettings(encryptPhieldApiKey(apiKey)));

        auditChanged(actingUserId, changed);

    }

    /**
     * Records which settings changed, by name. These gate security controls — the webhook allowlist,
     * multi-factor authentication, output signing — so a change to one has to be answerable later. The
     * value is never recorded: the Phield API key and the allowlist are what the log should not copy.
     */
    private void auditChanged(final ObjectId actingUserId, final List<String> changedKeys) {

        if (changedKeys.isEmpty()) {
            return;
        }

        auditEventPublisher.auditEvent(RequestIdGenerator.generate(), AuditLogEvent.SETTINGS_UPDATED,
                actingUserId, null, null, "settings: " + String.join(", ", changedKeys));

    }

    private List<String> updateSetting(final String key, final Object value) {
        return updateSettings(new Document(key, value));
    }

    /** Applies every field to the settings document in one update, and reports which ones changed. */
    private List<String> updateSettings(final Document fields) {

        final Document existing = collection.find(new Document()).first();

        final List<String> changed = new ArrayList<>();
        final List<Bson> updates = new ArrayList<>();

        fields.forEach((key, value) -> {
            updates.add(Updates.set(key, value));
            if (existing == null || !Objects.equals(existing.get(key), value)) {
                changed.add(key);
            }
        });

        collection.updateOne(new Document(), Updates.combine(updates), new UpdateOptions().upsert(true));

        writes.incrementAndGet();
        cachedAt = 0;

        return changed;

    }

}
