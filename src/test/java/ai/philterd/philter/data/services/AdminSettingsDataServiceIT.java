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
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        service = new AdminSettingsDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    @Test
    void findAdminSettingsReturnsNullBeforeAnySave() {
        assertNull(service.findAdminSettings());
    }

    @Test
    void saveLoggingEnabledPersists() {
        service.saveLoggingEnabled(true);

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertNotNull(settings);
        assertTrue(settings.isLoggingEnabled());

        service.saveLoggingEnabled(false);
        assertFalse(service.findAdminSettings().isLoggingEnabled());
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
        service.savePhieldSettings(true, "https://phield.example.com", "my-source", "my-org");

        final AdminSettingsEntity settings = service.findAdminSettings();
        assertNotNull(settings);
        assertTrue(settings.isPhieldEnabled());
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
        assertEquals("my-source", settings.getPhieldSourceId());
        assertEquals("my-org", settings.getPhieldOrganization());
    }

    @Test
    void savePhieldSettingsAppliesTrimmingAndDefaults() {
        // A blank source id / organization fall back to "philter"; the url is trimmed; null url -> "".
        service.savePhieldSettings(true, "  https://phield.example.com  ", "  ", "");

        AdminSettingsEntity settings = service.findAdminSettings();
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
        assertEquals("philter", settings.getPhieldSourceId());
        assertEquals("philter", settings.getPhieldOrganization());

        service.savePhieldSettings(false, null, "src", "org");
        settings = service.findAdminSettings();
        assertFalse(settings.isPhieldEnabled());
        assertEquals("", settings.getPhieldUrl());
        assertEquals("src", settings.getPhieldSourceId());
        assertEquals("org", settings.getPhieldOrganization());
    }

    @Test
    void settingsDocumentIsASingletonAcrossManySaves() {
        service.saveLoggingEnabled(true);
        service.saveDiffuseCountsEnabled(true);
        service.savePhieldSettings(true, "https://phield.example.com", "src", "org");
        service.saveLoggingEnabled(false);

        // Every save targets the same single document; no duplicates are created.
        final MongoCollection<Document> collection =
                mongoClient.getDatabase("philter").getCollection("admin_settings");
        assertEquals(1, collection.countDocuments());

        // Independent settings written across separate saves all coexist on the one document.
        final AdminSettingsEntity settings = service.findAdminSettings();
        assertFalse(settings.isLoggingEnabled());
        assertTrue(settings.isDiffuseCountsEnabled());
        assertTrue(settings.isPhieldEnabled());
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
    }

}
