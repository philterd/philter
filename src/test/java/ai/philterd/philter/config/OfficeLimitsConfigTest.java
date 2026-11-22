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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficeLimitsConfigTest {

    @Test
    void acceptsUnsetAndWholeNumbers() {
        assertDoesNotThrow(() -> OfficeLimitsConfig.validate(Map.<String, String>of()::get));
        assertDoesNotThrow(() -> OfficeLimitsConfig.validate(Map.of("OFFICE_MAX_UNCOMPRESSED_BYTES", " 1048576 ",
                "OFFICE_MAX_ENTRIES", "0")::get));
    }

    @Test
    void refusesAValueThatIsNotAWholeNumber() {
        assertThrows(IllegalStateException.class,
                () -> OfficeLimitsConfig.validate(Map.of("OFFICE_MAX_UNCOMPRESSED_BYTES", "100MB")::get));
        assertThrows(IllegalStateException.class,
                () -> OfficeLimitsConfig.validate(Map.of("OFFICE_MAX_ENTRIES", "10k")::get));
        // Entries must fit in an int.
        assertThrows(IllegalStateException.class,
                () -> OfficeLimitsConfig.validate(Map.of("OFFICE_MAX_ENTRIES", "3000000000")::get));
    }

}
