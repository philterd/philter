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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link AdminSettingsDataService} against a real (in-memory) MongoDB. These
 * verify that the service manages a single (singleton) settings document via upserts: saves persist,
 * {@code findAdminSettings} reflects them, and repeated saves update in place rather than creating
 * duplicate documents.
 */
class AdminSettingsDataServiceIT extends AbstractMongoIT {

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
        service.saveDiffuseCountsEnabled(true);

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertNotNull(settings);
        assertTrue(settings.isDiffuseCountsEnabled());

        service.saveDiffuseCountsEnabled(false);
        assertFalse(service.findAdminSettings().isDiffuseCountsEnabled());
    }

    @Test
    void savePhieldSettingsPersistsAllFields() {
        service.savePhieldSettings(true, "https://phield.example.com", "my-source", "my-org", "s3cret");

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
        service.savePhieldSettings(true, "  https://phield.example.com  ", "  ", "", "  s3cret  ");

        AdminSettingsEntity settings = service.findAdminSettings();
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
        assertEquals("philter", settings.getPhieldSourceId());
        assertEquals("philter", settings.getPhieldOrganization());
        assertEquals("s3cret", settings.getPhieldApiKey());

        service.savePhieldSettings(false, null, "src", "org", null);
        settings = service.findAdminSettings();
        assertFalse(settings.isPhieldEnabled());
        assertEquals("", settings.getPhieldUrl());
        assertEquals("src", settings.getPhieldSourceId());
        assertEquals("org", settings.getPhieldOrganization());
        assertEquals("", settings.getPhieldApiKey());
    }

    @Test
    void phieldApiKeyIsEncryptedAtRest() {
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org", "s3cret");

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
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org", "first-key");
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org", "second-key");
        assertEquals("second-key", service.findAdminSettings().getPhieldApiKey());

        // A save that clears the key must clear both halves, not leave the old data key behind.
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org", "");
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
        service.saveSigningEnabled(true);
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org", "s3cret");

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
        service.saveSigningEnabled(true);
        service.saveDiffuseCountsEnabled(true);
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org", "");
        service.saveSigningEnabled(false);

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

}
