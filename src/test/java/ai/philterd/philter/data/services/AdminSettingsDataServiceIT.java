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
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import org.bson.types.ObjectId;
import ai.philterd.philter.model.AuditLogEvent;
import org.mockito.ArgumentCaptor;

/**
 * Integration tests for {@link AdminSettingsDataService} against a real (in-memory) MongoDB. These
 * verify that the service manages a single (singleton) settings document via upserts: saves persist,
 * {@code findAdminSettings} reflects them, and repeated saves update in place rather than creating
 * duplicate documents.
 */
class AdminSettingsDataServiceIT extends AbstractMongoIT {

    private static final ObjectId ACTING_ADMIN = new ObjectId();

    private AdminSettingsDataService service;

    @BeforeEach
    void setUpService() {
        service = new AdminSettingsDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
    }

    @Test
    void findAdminSettingsReturnsNullBeforeAnySave() {
        assertNull(service.findAdminSettings());
    }

    @Test
    void saveDiffuseCountsEnabledPersists() {
        service.saveDiffuseCountsEnabled(ACTING_ADMIN, true);

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertNotNull(settings);
        assertTrue(settings.isDiffuseCountsEnabled());

        service.saveDiffuseCountsEnabled(ACTING_ADMIN, false);
        assertFalse(service.findAdminSettings().isDiffuseCountsEnabled());
    }

    @Test
    void savePhieldSettingsPersistsAllFields() {
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "my-source", "my-org", "s3cret");

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertNotNull(settings);
        assertTrue(settings.isPhieldEnabled());
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
        assertEquals("my-source", settings.getPhieldSourceId());
        assertEquals("my-org", settings.getPhieldOrganization());
        assertEquals("s3cret", settings.getPhieldApiKey());
    }

    @Test
    void savePhieldSettingsAppliesTrimmingAndDefaults() {
        // A blank source id / organization fall back to "philter"; the url and api key are trimmed;
        // a null url or api key becomes "".
        service.savePhieldSettings(ACTING_ADMIN, true, "  https://phield.example.com  ", "  ", "", "  s3cret  ");

        AdminSettingsEntity settings = service.findAdminSettings();
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
        assertEquals("philter", settings.getPhieldSourceId());
        assertEquals("philter", settings.getPhieldOrganization());
        assertEquals("s3cret", settings.getPhieldApiKey());

        service.savePhieldSettings(ACTING_ADMIN, false, null, "src", "org", null);
        settings = service.findAdminSettings();
        assertFalse(settings.isPhieldEnabled());
        assertEquals("", settings.getPhieldUrl());
        assertEquals("src", settings.getPhieldSourceId());
        assertEquals("org", settings.getPhieldOrganization());
        assertEquals("", settings.getPhieldApiKey());
    }

    @Test
    void phieldApiKeyIsEncryptedAtRest() {
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "s3cret");

        final Document stored = mongoClient.getDatabase("philter").getCollection("admin_settings").find().first();
        assertNotNull(stored);
        assertNotEquals("s3cret", stored.getString("phield_api_key"));
        assertFalse(stored.toJson().contains("s3cret"), "the api key must not be stored in the clear");
        // The wrapped data key is stored alongside the ciphertext, and only together do they decrypt.
        assertFalse(stored.getString("phield_api_key_key").isEmpty());

        assertEquals("s3cret", service.findAdminSettings().getPhieldApiKey());
    }

    @Test
    void thePhieldApiKeyAndItsDataKeyAreWrittenInOneUpdate() {
        // They are one logical value: a document carrying the ciphertext but the wrong data key (or
        // none) cannot be decrypted, so no interleaving save may ever leave the pair mismatched.
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "first-key");
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "second-key");
        assertEquals("second-key", service.findAdminSettings().getPhieldApiKey());

        // A save that clears the key must clear both halves, not leave the old data key behind.
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "");
        final Document stored = mongoClient.getDatabase("philter").getCollection("admin_settings").find().first();
        assertNotNull(stored);
        assertEquals("", stored.getString("phield_api_key"));
        assertEquals("", stored.getString("phield_api_key_key"));
        assertEquals("", service.findAdminSettings().getPhieldApiKey());
    }

    @Test
    void aPhieldApiKeyWrittenBeforeEncryptionIsStillReadable() {
        // Defensive: a value with no accompanying data key is plaintext from an earlier build.
        mongoClient.getDatabase("philter").getCollection("admin_settings")
                .insertOne(new Document("phield_api_key", "legacy-key"));

        assertEquals("legacy-key", service.findAdminSettings().getPhieldApiKey());
    }

    @Test
    void anUndecryptablePhieldApiKeyDoesNotBreakTheOtherSettings() {
        // Every read of the admin settings decrypts this field, including the ones output signing and
        // the dashboard make, so a key that cannot be decrypted must degrade to "unset" rather than throw.
        service.saveSigningEnabled(ACTING_ADMIN, true);
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "s3cret");

        final MongoCollection<Document> collection =
                mongoClient.getDatabase("philter").getCollection("admin_settings");
        collection.updateOne(new Document(), Updates.set("phield_api_key", "not-decryptable-with-that-key"));

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertNotNull(settings);
        assertEquals("", settings.getPhieldApiKey());
        // The rest of the settings survive.
        assertTrue(settings.isSigningEnabled());
        assertTrue(settings.isPhieldEnabled());
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
    }

    @Test
    void settingsDocumentIsASingletonAcrossManySaves() {
        service.saveSigningEnabled(ACTING_ADMIN, true);
        service.saveDiffuseCountsEnabled(ACTING_ADMIN, true);
        service.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "");
        service.saveSigningEnabled(ACTING_ADMIN, false);

        // Every save targets the same single document; no duplicates are created.
        final MongoCollection<Document> collection =
                mongoClient.getDatabase("philter").getCollection("admin_settings");
        assertEquals(1, collection.countDocuments());

        // Independent settings written across separate saves all coexist on the one document.
        final AdminSettingsEntity settings = service.findAdminSettings();
        assertFalse(settings.isSigningEnabled());
        assertTrue(settings.isDiffuseCountsEnabled());
        assertTrue(settings.isPhieldEnabled());
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
    }


    @Test
    @DisplayName("Settings are cached, and a write through the service evicts")
    void settingsAreCachedAndWritesEvict() {

        service.saveSigningEnabled(ACTING_ADMIN, true);
        assertTrue(service.findAdminSettings().isSigningEnabled());

        // Change it underneath the service. A cached read must not see this.
        mongoClient.getDatabase("philter").getCollection("admin_settings")
                .updateOne(new org.bson.Document(), new org.bson.Document("$set",
                        new org.bson.Document("signing_enabled", false)));

        assertTrue(service.findAdminSettings().isSigningEnabled(),
                "the settings are read once per redaction, so they must come from the cache");

        // A write through the service evicts, so the next read is fresh.
        service.saveSigningEnabled(ACTING_ADMIN, false);
        assertFalse(service.findAdminSettings().isSigningEnabled(),
                "a write must evict, or an admin's change would not take effect");

    }


    @Test
    @DisplayName("Changing a setting is audited, by name and never by value")
    void changingASettingIsAudited() {

        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        final AdminSettingsDataService audited = new AdminSettingsDataService(mongoClient, new TestEncryptionService(), publisher);

        audited.saveWebhookAllowlist(ACTING_ADMIN, "hooks.example.com, 10.4.0.0/16");

        final ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(publisher).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), eq(ACTING_ADMIN),
                isNull(), isNull(), details.capture());

        assertTrue(details.getValue().contains("webhook_allowlist"), "the setting must be named: " + details.getValue());
        assertFalse(details.getValue().contains("hooks.example.com"),
                "the allowlist itself must not be copied into the log: " + details.getValue());

    }

    @Test
    @DisplayName("Saving a setting that has not changed is not an event")
    void anUnchangedSettingIsNotAudited() {

        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        final AdminSettingsDataService audited = new AdminSettingsDataService(mongoClient, new TestEncryptionService(), publisher);

        audited.saveMfaEnabled(ACTING_ADMIN, true);
        verify(publisher).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), any(), any(), any(), any());

        // The Admin page saves every field on every click; only a change is worth recording.
        audited.saveMfaEnabled(ACTING_ADMIN, true);
        verifyNoMoreInteractions(publisher);

    }

    @Test
    @DisplayName("The Phield settings are audited together, and the API key is never named as a value")
    void phieldSettingsAreAuditedByField() {

        final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
        final AdminSettingsDataService audited = new AdminSettingsDataService(mongoClient, new TestEncryptionService(), publisher);

        audited.savePhieldSettings(ACTING_ADMIN, true, "https://phield.example.com", "src", "org", "s3cret-key");

        final ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(publisher).auditEvent(any(), eq(AuditLogEvent.SETTINGS_UPDATED), eq(ACTING_ADMIN),
                isNull(), isNull(), details.capture());

        assertTrue(details.getValue().contains("phield_enabled"));
        assertFalse(details.getValue().contains("s3cret-key"), "the API key must never reach the log");
        assertFalse(details.getValue().contains("phield.example.com"), "nor the URL");

    }

}
