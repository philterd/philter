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

import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Caches the {@link PhileasConfiguration} per span-disambiguation on/off setting, so it is built once
 * per variant instead of on every request. The disambiguation flag is the only request-varying config
 * input, so there are at most two instances. They are immutable and safe to share across concurrent
 * requests, and keying by the flag ensures each request gets the configuration for its own setting.
 */
final class PhileasConfigurationCache {

    private final ConcurrentMap<Boolean, PhileasConfiguration> cache = new ConcurrentHashMap<>();

    /**
     * Returns the configuration for the given disambiguation setting, building it on first use and
     * reusing it thereafter.
     */
    PhileasConfiguration get(final boolean spanDisambiguationEnabled) {
        return cache.computeIfAbsent(spanDisambiguationEnabled, PhileasConfigurationCache::build);
    }

    private static PhileasConfiguration build(final boolean spanDisambiguationEnabled) {

        final Properties properties = new Properties();

        // Backs the ledger; defaults on, overridable via env. Fixed per process, so reading it once
        // here matches the previous per-request read.
        properties.put("incremental.redactions.enabled",
                System.getenv().getOrDefault("INCREMENTAL_REDACTIONS_ENABLED", "true"));

        // The single switch for the span-disambiguation engine.
        properties.put("span.disambiguation.enabled", Boolean.toString(spanDisambiguationEnabled));

        return new PhileasConfiguration(properties);

    }

}
