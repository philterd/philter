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
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** {@link AdminSettingsDataService#update} against a real (in-memory) MongoDB. */
class AdminSettingsUpdateIT extends AbstractMongoIT {

    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);
    private AdminSettingsDataService service;
    private ObjectId admin;
    private final ObjectId key = new ObjectId();

    @BeforeEach
    void setUp() {
        service = new AdminSettingsDataService(mongoClient, new TestEncryptionService(), audit);
        admin = user("admin", false);
    }

    private ObjectId user(final String role, final boolean deactivated) {
        final ObjectId id = new ObjectId();
        mongoClient.getDatabase("philter").getCollection("users").insertOne(new Document("_id", id)
                .append("username", "u-" + id).append("role", role)
                .append("deactivated", deactivated));
        return id;
    }

    private static AdminSettingsDataService.Update update(final Boolean diffuse, final Boolean signing,
                                                          final String allowlist, final Boolean phield,
                                                          final String url, final String apiKey) {
        return new AdminSettingsDataService.Update(diffuse, signing, allowlist, phield, url, null, null, apiKey, null, null);
    }

    @Test
    @DisplayName("Only the settings given are changed, and the audit names them and the key, not their values")
    void changesOnlyWhatIsGiven() {
        service.update(update(true, true, "hooks.example.com", null, null, null), admin, key);
        service.update(update(null, false, null, null, null, null), admin, key);

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertTrue(settings.isDiffuseCountsEnabled(), "left unchanged");
        assertFalse(settings.isSigningEnabled());
        assertEquals("hooks.example.com", settings.getWebhookAllowlist());

        verify(audit).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), eq(admin), eq(null), eq(null),
                eq("settings: signing_enabled, api_key: " + key));
    }

    @Test
    @DisplayName("An unchanged value is not audited")
    void noChangeNoAudit() {
        service.update(update(true, null, null, null, null, null), admin, key);
        service.update(update(true, null, null, null, null, null), admin, key);
        verify(audit).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), eq(admin), eq(null), eq(null),
                eq("settings: diffuse_counts_enabled, api_key: " + key));
    }

    @Test
    @DisplayName("An invalid value is refused with the reason, and nothing is written")
    void refusesInvalidValuesWithoutWriting() {
        final IllegalArgumentException allowlist = assertThrows(IllegalArgumentException.class,
                () -> service.update(update(true, null, "10.0.0.0/99", null, null, null), admin, key));
        assertTrue(allowlist.getMessage().contains("10.0.0.0/99"), allowlist.getMessage());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.update(update(true, null, null, null, "ftp://phield.example.com", null), admin, key))
                .getMessage().contains("http or https"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> service.update(update(true, null, null, true, null, null), admin, key))
                .getMessage().contains("required when Phield is enabled"));

        // The diffuse flag sent alongside each bad value was not written.
        assertTrue(service.findAdminSettings() == null || !service.findAdminSettings().isDiffuseCountsEnabled());
        verify(audit, never()).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Phield: the API key is kept when left out, removed when empty, and an http URL with a key warns")
    void phieldKeyAndWarning() {
        final List<String> warnings = service.update(
                update(null, null, null, true, "http://phield.example.com", "phield-secret"), admin, key);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("in the clear"));

        service.update(update(null, null, null, null, "https://phield.example.com", null), admin, key);
        assertEquals("phield-secret", service.findAdminSettings().getPhieldApiKey(), "left out, so kept");

        service.update(update(null, null, null, null, null, ""), admin, key);
        assertEquals("", service.findAdminSettings().getPhieldApiKey(), "empty, so removed");

        // A key is stored encrypted.
        final Document stored = mongoClient.getDatabase("philter").getCollection("admin_settings").find().first();
        assertFalse(String.valueOf(stored.get("phield_api_key")).contains("phield-secret"));
    }

    @Test
    @DisplayName("Only a current, active administrator may change settings")
    void requiresAnActiveAdministrator() {
        for (final ObjectId notAllowed : List.of(user("user", false), user("admin", true),
                new ObjectId())) {
            assertThrows(AccessDeniedException.class,
                    () -> service.update(update(true, null, null, null, null, null), notAllowed, key));
        }
        verify(audit, never()).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A Phield value saved without these checks does not block an unrelated change")
    void storedPhieldValuesDoNotBlockOtherChanges() {
        // As an earlier version could leave them: enabled with a URL that is not one.
        mongoClient.getDatabase("philter").getCollection("admin_settings").insertOne(
                new Document("phield_enabled", true).append("phield_url", "phield:8080"));

        service.update(update(null, true, null, null, null, null), admin, key);
        assertTrue(service.findAdminSettings().isSigningEnabled());

        // Changing Phield itself is still checked.
        assertThrows(IllegalArgumentException.class,
                () -> service.update(update(null, null, null, null, "phield:9090", null), admin, key));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(update(null, null, null, true, "", null), admin, key));
    }

}
