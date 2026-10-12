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
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.webhook.WebhookDestinationPolicy;
import ai.philterd.philter.utils.EnvUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The rules for the admin settings, the same in every storage implementation: who may change them, what a valid
 * change is, which changes are audited, how the Phield API key is encrypted, and how long an instance caches
 * them. An implementation stores the one settings record.
 */
public abstract class StoredAdminSettingsDataService implements AdminSettingsDataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminSettingsDataService.class);

    /** The Phield API key belongs to the instance, not a user; the key provider ignores this value. */
    private static final String SYSTEM_OWNER = "system";

    /**
     * One record, read on every redaction and changed rarely. Writes here evict; the TTL bounds how long
     * another instance's write takes to be seen. Callers treat the entity as read-only.
     */
    private static final long CACHE_TTL_MILLIS = EnvUtils.getInt("ADMIN_SETTINGS_CACHE_TTL_SECONDS", 60) * 1000L;

    /** Each setting, by the name the audit log records it under. */
    public enum Setting {
        DIFFUSE_COUNTS_ENABLED("diffuse_counts_enabled"),
        SIGNING_ENABLED("signing_enabled"),
        WEBHOOK_ALLOWLIST("webhook_allowlist"),
        PHIELD_ENABLED("phield_enabled"),
        PHIELD_URL("phield_url"),
        PHIELD_SOURCE_ID("phield_source_id"),
        PHIELD_ORGANIZATION("phield_organization"),
        PHIELD_API_KEY("phield_api_key"),
        MFA_AVAILABLE("mfa_available"),
        MFA_REQUIRED("mfa_required");

        private final String name;

        Setting(final String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    /** The stored settings, with the Phield API key as stored: sealed, or null when none was ever set. */
    public record StoredSettings(AdminSettingsEntity settings, SecretFields.Sealed phieldApiKey) {
    }

    protected final AuditEventPublisher auditEventPublisher;
    private final EncryptionService encryptionService;

    private volatile AdminSettingsEntity cached;
    private volatile long cachedAt;
    private final java.util.concurrent.atomic.AtomicLong writes = new java.util.concurrent.atomic.AtomicLong();

    protected StoredAdminSettingsDataService(final EncryptionService encryptionService,
                                             final AuditEventPublisher auditEventPublisher) {
        this.encryptionService = encryptionService;
        this.auditEventPublisher = auditEventPublisher;
    }

    /** The stored settings, or null when none have been saved. */
    protected abstract StoredSettings read();

    /**
     * Sets the given settings in one write, creating the settings record when there is none. A Boolean setting's
     * value is a Boolean, {@link Setting#PHIELD_API_KEY}'s a {@link SecretFields.Sealed}, and the others' a
     * String.
     */
    protected abstract void write(final Map<Setting, Object> values);

    /** Whether the user exists, is an administrator, and is not deactivated, read from the stored users. */
    protected abstract boolean isActiveAdministrator(final String userId);

    @Override
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
        final StoredSettings stored = read();
        if (stored == null) {
            return null;
        }
        stored.settings().setPhieldApiKey(decryptPhieldApiKey(stored.phieldApiKey()));
        return stored.settings();
    }

    @Override
    public List<String> update(final Update update, final String actingUserId, final String actingApiKeyId) {
        return update(RequestIdGenerator.generate(), update, actingUserId, actingApiKeyId);
    }

    @Override
    public List<String> update(final String requestId, final Update update, final String actingUserId,
                               final String actingApiKeyId) {

        if (actingUserId == null || !isActiveAdministrator(actingUserId)) {
            throw new AccessDeniedException("Current administrator authorization required.");
        }

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

        final Map<Setting, Object> values = new EnumMap<>(Setting.class);
        putIfGiven(values, Setting.DIFFUSE_COUNTS_ENABLED, update.diffuseCountsEnabled());
        putIfGiven(values, Setting.SIGNING_ENABLED, update.signingEnabled());
        putIfGiven(values, Setting.WEBHOOK_ALLOWLIST, update.webhookAllowlist() == null ? null : update.webhookAllowlist().trim());
        putIfGiven(values, Setting.PHIELD_ENABLED, update.phieldEnabled());
        putIfGiven(values, Setting.PHIELD_URL, update.phieldUrl() == null ? null : url);
        putIfGiven(values, Setting.PHIELD_SOURCE_ID, orPhilter(update.phieldSourceId()));
        putIfGiven(values, Setting.PHIELD_ORGANIZATION, orPhilter(update.phieldOrganization()));
        putIfGiven(values, Setting.MFA_AVAILABLE, update.mfaAvailable());
        putIfGiven(values, Setting.MFA_REQUIRED, update.mfaRequired());

        final List<String> changed = new ArrayList<>();
        for (final Map.Entry<Setting, Object> entry : values.entrySet()) {
            if (current == null || !Objects.equals(currentValue(current, entry.getKey()), entry.getValue())) {
                changed.add(entry.getKey().getName());
            }
        }
        if (update.phieldApiKey() != null) {
            // Always recorded as changed: the stored value is newly encrypted whatever it holds.
            values.put(Setting.PHIELD_API_KEY, encryptPhieldApiKey(update.phieldApiKey()));
            changed.add(Setting.PHIELD_API_KEY.getName());
        }
        changed.sort(java.util.Comparator.comparing(name -> settingNamed(name).ordinal()));

        if (!values.isEmpty()) {
            write(values);
            writes.incrementAndGet();
            cachedAt = 0;
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

    private static void putIfGiven(final Map<Setting, Object> values, final Setting setting, final Object value) {
        if (value != null) {
            values.put(setting, value);
        }
    }

    private static String orPhilter(final String value) {
        return value == null ? null : value.isBlank() ? "philter" : value.trim();
    }

    private static Setting settingNamed(final String name) {
        for (final Setting setting : Setting.values()) {
            if (setting.getName().equals(name)) {
                return setting;
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static Object currentValue(final AdminSettingsEntity current, final Setting setting) {
        return switch (setting) {
            case DIFFUSE_COUNTS_ENABLED -> current.isDiffuseCountsEnabled();
            case SIGNING_ENABLED -> current.isSigningEnabled();
            case WEBHOOK_ALLOWLIST -> current.getWebhookAllowlist();
            case PHIELD_ENABLED -> current.isPhieldEnabled();
            case PHIELD_URL -> current.getPhieldUrl();
            case PHIELD_SOURCE_ID -> current.getPhieldSourceId();
            case PHIELD_ORGANIZATION -> current.getPhieldOrganization();
            case PHIELD_API_KEY -> current.getPhieldApiKey();
            case MFA_AVAILABLE -> current.isMfaAvailable();
            case MFA_REQUIRED -> current.isMfaRequired();
        };
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
    private String decryptPhieldApiKey(final SecretFields.Sealed stored) {

        if (stored == null || stored.ciphertext() == null || stored.ciphertext().isEmpty()) {
            return "";
        }

        if (stored.key() == null || stored.key().isEmpty()) {
            return stored.ciphertext();
        }

        try {
            return encryptionService.decrypt(stored.ciphertext(), stored.key());
        } catch (final Exception ex) {
            LOGGER.warn("Unable to decrypt the stored Phield API key; treating it as unset. Re-enter it with "
                    + "PATCH /api/settings if the Phield instance requires one. Cause: {}", ex.getMessage());
            return "";
        }

    }

    /** Encrypts the Phield API key for storage. A blank key is stored as empty and read back as "". */
    private SecretFields.Sealed encryptPhieldApiKey(final String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return new SecretFields.Sealed("", "");
        }
        return SecretFields.seal(encryptionService, apiKey.trim(), SYSTEM_OWNER);
    }

}
