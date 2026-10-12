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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.UserService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link AdminSettingsDataService}. */
public interface AdminSettingsDataServiceContract extends StorageContract {

    private String administrator() {
        assertTrue(storage().users().createUser("req", "admin", null, UserService.ROLE_ADMIN, storage().policies(),
                storage().contexts(), "test").isSuccessful());
        return storage().users().findByUsername("admin").getId();
    }

    @Test
    default void adminSettingsAnUpdateChangesOnlyTheSettingsItNames() {
        final String admin = administrator();
        storage().adminSettings().update(new AdminSettingsDataService.Update(true, null, "example.com", null,
                null, null, null, null, null, null), admin, null);
        storage().anotherInstance().adminSettings().update(new AdminSettingsDataService.Update(null, null, null, null,
                "https://phield.example.com", null, null, null, null, null), admin, null);
        final AdminSettingsEntity settings = storage().anotherInstance().adminSettings().findAdminSettings();
        assertTrue(settings.isDiffuseCountsEnabled());
        assertEquals("example.com", settings.getWebhookAllowlist());
        assertEquals("https://phield.example.com", settings.getPhieldUrl());
    }

    @Test
    default void adminSettingsSecretsReadBackUnchanged() {
        final String admin = administrator();
        storage().adminSettings().update(new AdminSettingsDataService.Update(null, null, null, null, null, null, null,
                "a key, \"quoted\", Ünïcödé", null, null), admin, null);
        assertEquals("a key, \"quoted\", Ünïcödé",
                storage().anotherInstance().adminSettings().findAdminSettings().getPhieldApiKey());
    }

}
