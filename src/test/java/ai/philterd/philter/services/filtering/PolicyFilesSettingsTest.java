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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.PhileasConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyFilesSettingsTest {

    @TempDir
    Path temp;

    private static PolicyFilesSettings settings(final Map<String, String> environment) {
        return PolicyFilesSettings.fromEnvironment(environment::get);
    }

    @Test
    void unsetAllowsNoFileAndTheDefaultLimit() {
        final PolicyFilesSettings settings = settings(Map.of());
        assertEquals(List.of(), settings.directories());
        assertEquals(10L * 1024 * 1024, settings.maxBytes());
    }

    @Test
    void directoriesAreResolvedAndTheLimitRead() throws Exception {
        final Path one = Files.createDirectories(temp.resolve("one"));
        final Path two = Files.createDirectories(temp.resolve("two"));
        final PolicyFilesSettings settings = settings(Map.of("POLICY_FILES_DIRECTORIES", " " + one + " , " + two + "/../two ",
                "POLICY_FILES_MAX_BYTES", "2048"));
        assertEquals(List.of(one.toRealPath().toString(), two.toRealPath().toString()), settings.directories());
        assertEquals(2048, settings.maxBytes());
    }

    @Test
    void aMistakeStopsPhilter() throws Exception {
        final Path file = Files.writeString(temp.resolve("file.txt"), "x");
        assertTrue(assertThrows(IllegalStateException.class, () -> settings(Map.of("POLICY_FILES_DIRECTORIES", temp.resolve("missing").toString())))
                .getMessage().endsWith("which does not exist."));
        assertTrue(assertThrows(IllegalStateException.class, () -> settings(Map.of("POLICY_FILES_DIRECTORIES", file.toString())))
                .getMessage().endsWith("which is not a directory."));
        assertTrue(assertThrows(IllegalStateException.class, () -> settings(Map.of("POLICY_FILES_DIRECTORIES", "/")))
                .getMessage().startsWith("POLICY_FILES_DIRECTORIES cannot name the filesystem root"));
        for (final String limit : new String[]{"0", "-1", "ten", "1.5"}) {
            assertEquals("POLICY_FILES_MAX_BYTES must be a positive number of bytes.",
                    assertThrows(IllegalStateException.class, () -> settings(Map.of("POLICY_FILES_MAX_BYTES", limit))).getMessage(), limit);
        }
    }

    @Test
    void phileasIsAlwaysRestricted() throws Exception {
        final Path one = Files.createDirectories(temp.resolve("one"));
        for (final Map<String, String> environment : List.of(Map.<String, String>of(), Map.of("POLICY_FILES_DIRECTORIES", one.toString()))) {
            final Properties properties = new Properties();
            settings(environment).applyTo(properties);
            final PhileasConfiguration configuration = new PhileasConfiguration(properties);
            assertTrue(configuration.policyFilesRestricted());
            assertEquals(environment.isEmpty() ? List.of() : List.of(one.toRealPath().toString()), configuration.policyFilesDirectories());
            assertEquals(10L * 1024 * 1024, configuration.policyFilesMaxBytes());
        }
    }

    @Test
    void aPhileasPolicyFileSettingSetDirectlyStopsPhilter() {
        PolicyFilesSettings.refuseDirectPhileasSettings(name -> null, name -> null);
        for (final String property : PolicyFilesSettings.PHILEAS_PROPERTIES) {
            final String fromEnvironment = assertThrows(IllegalStateException.class, () -> PolicyFilesSettings.refuseDirectPhileasSettings(
                    name -> name.equals(property) ? "false" : null, name -> null)).getMessage();
            assertTrue(fromEnvironment.startsWith(property + " is set directly"), fromEnvironment);
            assertThrows(IllegalStateException.class, () -> PolicyFilesSettings.refuseDirectPhileasSettings(
                    name -> null, name -> name.equals(property) ? "/" : null));
        }
    }

}
