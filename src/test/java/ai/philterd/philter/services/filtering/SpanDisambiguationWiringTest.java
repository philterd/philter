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

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the contract that {@link RedactionService} relies on to make a context's entity type
 * disambiguation flag the single switch for the feature: the {@code span.disambiguation.enabled}
 * property it sets (from the context flag) must drive the Phileas engine's enabled state.
 *
 * <p>{@link RedactionService#filter} cannot be unit-tested without the full Mongo/Phileas pipeline,
 * so this test pins the exact property mapping the fix depends on. {@code RedactionService} sets
 * {@code span.disambiguation.enabled} to {@code Boolean.toString(contextEntity.isDisambiguation())};
 * these cases confirm both values resolve correctly through {@link PhileasConfiguration}.
 */
class SpanDisambiguationWiringTest {

    private PhileasConfiguration configurationForContextFlag(final boolean disambiguationEnabled) {
        // Mirrors how RedactionService builds the PhileasConfiguration for a request.
        final Properties properties = new Properties();
        properties.put("incremental.redactions.enabled", "true");
        properties.put("span.disambiguation.enabled", Boolean.toString(disambiguationEnabled));
        return new PhileasConfiguration(properties);
    }

    @Test
    void contextWithDisambiguationEnablesTheEngine() {
        assertTrue(configurationForContextFlag(true).spanDisambiguationEnabled(),
                "a context with disambiguation enabled must enable the Phileas span-disambiguation engine");
    }

    @Test
    void contextWithoutDisambiguationLeavesTheEngineOff() {
        assertFalse(configurationForContextFlag(false).spanDisambiguationEnabled(),
                "a context without disambiguation must leave the engine off");
    }

}
