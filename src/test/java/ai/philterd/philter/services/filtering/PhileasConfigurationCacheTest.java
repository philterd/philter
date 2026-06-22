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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PhileasConfigurationCache}: the configuration is built once per span-disambiguation
 * variant and reused, and keyed by the flag so each request gets the configuration for its own setting.
 */
class PhileasConfigurationCacheTest {

    @Test
    void enabledFlagProducesEnabledConfiguration() {
        final PhileasConfiguration configuration = new PhileasConfigurationCache().get(true);
        assertTrue(configuration.spanDisambiguationEnabled(),
                "a request with disambiguation enabled must get a configuration that enables the engine");
    }

    @Test
    void disabledFlagProducesDisabledConfiguration() {
        final PhileasConfiguration configuration = new PhileasConfigurationCache().get(false);
        assertFalse(configuration.spanDisambiguationEnabled(),
                "a request with disambiguation disabled must get a configuration that leaves the engine off");
    }

    @Test
    void incrementalRedactionsDefaultsToEnabled() {
        // Preserve the default-on behavior the inline build had.
        assertTrue(new PhileasConfigurationCache().get(true).incrementalRedactionsEnabled(),
                "incremental redactions must default to enabled");
        assertTrue(new PhileasConfigurationCache().get(false).incrementalRedactionsEnabled(),
                "incremental redactions must default to enabled");
    }

    @Test
    void sameFlagReusesTheSameInstance() {
        final PhileasConfigurationCache cache = new PhileasConfigurationCache();
        assertSame(cache.get(true), cache.get(true),
                "repeated requests with the same disambiguation setting must reuse the configuration");
        assertSame(cache.get(false), cache.get(false),
                "repeated requests with the same disambiguation setting must reuse the configuration");
    }

    @Test
    void differentFlagsGetDistinctInstances() {
        final PhileasConfigurationCache cache = new PhileasConfigurationCache();
        assertNotSame(cache.get(true), cache.get(false),
                "the enabled and disabled configurations must be distinct instances");
    }

    /**
     * Regression: caching must not let one request's disambiguation setting stick for later requests.
     * Sequential requests with different settings each get the correct configuration, in both orders.
     */
    @Test
    void sequentialRequestsWithDifferentSettingsEachGetCorrectConfiguration() {

        final PhileasConfigurationCache cache = new PhileasConfigurationCache();

        // Enabled then disabled: the disabled request must not inherit the enabled setting.
        assertTrue(cache.get(true).spanDisambiguationEnabled());
        assertFalse(cache.get(false).spanDisambiguationEnabled(),
                "a disabled-disambiguation request after an enabled one must still be disabled");

        // Disabled then enabled, on a fresh cache: the enabled request must not inherit the disabled setting.
        final PhileasConfigurationCache reverse = new PhileasConfigurationCache();
        assertFalse(reverse.get(false).spanDisambiguationEnabled());
        assertTrue(reverse.get(true).spanDisambiguationEnabled(),
                "an enabled-disambiguation request after a disabled one must still be enabled");

        // Interleaved repeatedly: each call reflects its own flag.
        for (int i = 0; i < 5; i++) {
            assertTrue(cache.get(true).spanDisambiguationEnabled());
            assertFalse(cache.get(false).spanDisambiguationEnabled());
        }

    }

}
