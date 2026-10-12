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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageConfigurationTest {

    @Test
    void mongodbIsAcceptedInAnyCase() {
        assertDoesNotThrow(() -> new StorageConfiguration("mongodb"));
        assertDoesNotThrow(() -> new StorageConfiguration(" MongoDB "));
    }

    @Test
    void anUnknownStorageTypeStopsStartup() {
        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new StorageConfiguration("postgres"));
        assertTrue(refused.getMessage().contains("STORAGE_TYPE must be one of mongodb"), refused.getMessage());
        assertThrows(IllegalStateException.class, () -> StorageConfiguration.requireKnown(""));
        assertThrows(IllegalStateException.class, () -> StorageConfiguration.requireKnown(null));
    }

}
