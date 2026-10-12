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
package ai.philterd.philter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quick start is what most people run first, and for some it is what they keep. It shipped with
 * admin cross-user access on — the one setting the application prints a warning banner about at
 * startup — so the bundled deployment was the state the code warns against.
 *
 * <p>These are the switches whose safe value is off. A line that turns one on has to be commented
 * out, which is a deliberate act rather than a default nobody chose.
 */
class ShippedComposeDefaultsTest {


    private static final List<String> MUST_NOT_BE_ENABLED = List.of(
            "ADMIN_CROSS_USER_ACCESS_ENABLED",
            "TLS_TRUST_ALL_ENABLED",
            "LEDGER_DELETION_ENABLED");

    @ParameterizedTest
    @ValueSource(strings = {"docker-compose.yml", "docker-compose.mongodb.yml"})
    @DisplayName("The bundled compose files turn on nothing the application warns about")
    void theQuickStartShipsWithTheSecureDefaults(final String file) throws IOException {

        final Path compose = Path.of(file);

        final List<String> enabled = new ArrayList<>();
        int checked = 0;

        for (final String line : Files.readAllLines(compose)) {

            final String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue;
            }

            for (final String setting : MUST_NOT_BE_ENABLED) {
                if (trimmed.startsWith(setting + ":")) {
                    checked++;
                    if (trimmed.toLowerCase().endsWith("true")) {
                        enabled.add(trimmed);
                    }
                }
            }

        }

        assertEquals(List.of(), enabled,
                file + " enables a setting the application warns about at startup");

        // The file must still be the one being read, or this passes by looking at nothing.
        assertTrue(Files.readString(compose).contains("ADMIN_CROSS_USER_ACCESS_ENABLED"),
                "the compose file no longer mentions the setting; this check has stopped checking");
        assertEquals(0, checked, "these settings are expected to be commented out, not set to false");

    }

}
