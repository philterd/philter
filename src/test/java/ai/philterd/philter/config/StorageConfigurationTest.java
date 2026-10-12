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
package ai.philterd.philter.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigurationTest {

    private static StorageConfiguration configured(final String... properties) {
        final MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        return new StorageConfiguration(environment);
    }

    @Test
    void sqliteIsTheDefaultAndEitherStorageIsAcceptedInAnyCase() {
        assertDoesNotThrow(() -> configured());
        assertDoesNotThrow(() -> configured(StorageConfiguration.PROPERTY, "mongodb", "STORAGE_TYPE", "mongodb"));
        assertDoesNotThrow(() -> configured(StorageConfiguration.PROPERTY, "MongoDB", "STORAGE_TYPE", "MongoDB"));
        assertDoesNotThrow(() -> configured(StorageConfiguration.PROPERTY, "SQLite", "STORAGE_TYPE", "SQLite"));
    }

    @Test
    void anUnknownStorageTypeStopsStartup() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> configured(StorageConfiguration.PROPERTY, "postgres", "STORAGE_TYPE", "postgres"));
        assertTrue(refused.getMessage().contains("STORAGE_TYPE must be one of mongodb, sqlite"), refused.getMessage());
        assertThrows(IllegalStateException.class, () -> StorageConfiguration.requireKnown(""));
        // A padded value would select no storage at all, so it is refused rather than accepted.
        assertThrows(IllegalStateException.class, () -> StorageConfiguration.requireKnown(" mongodb "));
        assertThrows(IllegalStateException.class, () -> StorageConfiguration.requireKnown(null));
    }

    @Test
    void aMongoDbConnectionWithNoStorageChosenStopsStartup() {
        // As a Philter 3 deployment upgraded without setting STORAGE_TYPE would be.
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> configured("MONGODB_CONNECTION_STRING", "mongodb://mongodb:27017"));
        assertTrue(refused.getMessage().contains("set STORAGE_TYPE=mongodb"), refused.getMessage());
        assertDoesNotThrow(() -> configured("MONGODB_CONNECTION_STRING", "mongodb://mongodb:27017",
                StorageConfiguration.PROPERTY, "mongodb", "STORAGE_TYPE", "mongodb"));
        assertDoesNotThrow(() -> configured("MONGODB_CONNECTION_STRING", "mongodb://mongodb:27017",
                StorageConfiguration.PROPERTY, "sqlite", "STORAGE_TYPE", "sqlite"));
    }

}
