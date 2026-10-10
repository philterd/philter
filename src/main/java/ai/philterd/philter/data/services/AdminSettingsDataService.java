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

import ai.philterd.philter.api.security.AdministratorAuthorization;
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
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.webhook.WebhookDestinationPolicy;
import java.net.URI;
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
        final AdminSettingsEntity adminSettingsEntity = load();

        // Not if a write landed mid-read; that would cache the pre-write state for a full TTL.
        if (writes.get() == seen) {
            cached = adminSettingsEntity;
            cachedAt = now;
        }

        return adminSettingsEntity;

    }

    /** The stored settings, read past the cache, or {@code null} when none have been saved. */
    private AdminSettingsEntity load() {
        final Document document = collection.find().first();
        if (document == null) {
            return null;
        }
        final AdminSettingsEntity adminSettingsEntity = AdminSettingsEntity.fromDocument(document);
        adminSettingsEntity.setPhieldApiKey(decryptPhieldApiKey(document));
        return adminSettingsEntity;
    }

    /**
     * Settings to change. A null field is left as it is. For the Phield API key, {@code ""} removes it.
     */
    public record Update(Boolean diffuseCountsEnabled, Boolean signingEnabled, String webhookAllowlist,
                         Boolean phieldEnabled, String phieldUrl, String phieldSourceId,
                         String phieldOrganization, String phieldApiKey,
                         Boolean mfaAvailable, Boolean mfaRequired) {
    }

    /**
     * Validates and applies an {@link Update} from the API, after confirming the acting user is still an
     * active administrator. Nothing is written unless every value is valid. The change is audited as
     * {@code settings_updated}, naming the settings that changed and the acting API key, never the values.
     *
     * @return warnings about the saved settings, such as a Phield API key that will be sent over http.
     * @throws IllegalArgumentException with the reason, when a value is not valid.
     */
    public List<String> update(final Update update, final ObjectId actingUserId, final ObjectId actingApiKeyId) {
        return update(RequestIdGenerator.generate(), update, actingUserId, actingApiKeyId);
    }

    /** As above, recording the change under {@code requestId}, the id of the request that made it. */
    public List<String> update(final String requestId, final Update update, final ObjectId actingUserId,
                               final ObjectId actingApiKeyId) {

        AdministratorAuthorization.requireActiveAdministrator(mongoClient, actingUserId);

        final AdminSettingsEntity current = load();

        final String invalid = WebhookDestinationPolicy.invalidEntry(update.webhookAllowlist());
        if (invalid != null) {
            throw new IllegalArgumentException("webhookAllowlist entry '" + invalid
                    + "' is not a hostname, an IP address, or a CIDR range.");
        }

        final String url = update.phieldUrl() != null ? update.phieldUrl().trim()
                : current == null ? "" : current.getPhieldUrl();
        final boolean phieldEnabled = update.phieldEnabled() != null ? update.phieldEnabled()
                : current != null && current.isPhieldEnabled();
        final String apiKey = update.phieldApiKey() != null ? update.phieldApiKey().trim()
                : current == null ? "" : current.getPhieldApiKey();

        // Checked only when this request changes Phield, so a value saved before these checks existed
        // does not block an unrelated change.
        if (update.phieldUrl() != null && !url.isEmpty() && !isHttpUrl(url)) {
            throw new IllegalArgumentException("phieldUrl must be an absolute http or https URL with a host.");
        }
        if ((update.phieldEnabled() != null || update.phieldUrl() != null) && phieldEnabled
                && (url == null || url.isEmpty())) {
            throw new IllegalArgumentException("phieldUrl is required when Phield is enabled.");
        }

        final boolean mfaAvailable = update.mfaAvailable() != null ? update.mfaAvailable()
                : current != null && current.isMfaAvailable();
        final boolean mfaRequired = update.mfaRequired() != null ? update.mfaRequired()
                : current != null && current.isMfaRequired();
        if (mfaRequired && !mfaAvailable) {
            throw new IllegalArgumentException("mfaRequired needs mfaAvailable: users cannot be required to enroll "
                    + "in MFA that is not available.");
        }

        final List<String> changed = new ArrayList<>();
        if (update.diffuseCountsEnabled() != null) {
            changed.addAll(updateSetting("diffuse_counts_enabled", update.diffuseCountsEnabled()));
        }
        if (update.signingEnabled() != null) {
            changed.addAll(updateSetting("signing_enabled", update.signingEnabled()));
        }
        if (update.webhookAllowlist() != null) {
            changed.addAll(updateSetting("webhook_allowlist", update.webhookAllowlist().trim()));
        }
        if (update.phieldEnabled() != null) {
            changed.addAll(updateSetting("phield_enabled", update.phieldEnabled()));
        }
        if (update.phieldUrl() != null) {
            changed.addAll(updateSetting("phield_url", url));
        }
        if (update.phieldSourceId() != null) {
            changed.addAll(updateSetting("phield_source_id",
                    update.phieldSourceId().isBlank() ? "philter" : update.phieldSourceId().trim()));
        }
        if (update.phieldOrganization() != null) {
            changed.addAll(updateSetting("phield_organization",
                    update.phieldOrganization().isBlank() ? "philter" : update.phieldOrganization().trim()));
        }
        if (update.phieldApiKey() != null) {
            changed.addAll(updateSettings(encryptPhieldApiKey(update.phieldApiKey())));
        }
        if (update.mfaAvailable() != null) {
            changed.addAll(updateSetting("mfa_available", update.mfaAvailable()));
        }
        if (update.mfaRequired() != null) {
            changed.addAll(updateSetting("mfa_required", update.mfaRequired()));
        }

        if (!changed.isEmpty()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.SETTINGS_UPDATED,
                    actingUserId, null, null, "settings: " + String.join(", ", changed) + ", api_key: " + actingApiKeyId);
        }

        final List<String> warnings = new ArrayList<>();
        if (phieldEnabled && PhieldPublisher.sendsApiKeyInTheClear(url, apiKey)) {
            warnings.add("The Phield URL is http, so the API key is sent in the clear. Use an https URL.");
        }
        return warnings;

    }

    private static boolean isHttpUrl(final String url) {
        try {
            final URI uri = URI.create(url);
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (final IllegalArgumentException notAUri) {
            return false;
        }
    }

    /**
     * Decrypts the stored Phield API key. A value with no accompanying data key was set directly in
     * the database and is returned as-is. A value that cannot be decrypted is reported as unset rather
     * than thrown: every read of the admin settings comes through here, so failing would take output
     * signing and {@code GET /api/settings} down with it, and {@code PATCH /api/settings} is where the
     * key would be re-entered.
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
            LOGGER.warn("Unable to decrypt the stored Phield API key; treating it as unset. Re-enter it with "
                    + "PATCH /api/settings if the Phield instance requires one. Cause: {}", ex.getMessage());
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
