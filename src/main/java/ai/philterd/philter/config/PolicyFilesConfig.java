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

import ai.philterd.philter.services.filtering.PolicyFilesSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Checks {@code POLICY_FILES_DIRECTORIES} and {@code POLICY_FILES_MAX_BYTES} when Philter starts, so a mistake in them
 * stops Philter instead of silently allowing or refusing the files policies name.
 */
@Configuration
public class PolicyFilesConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyFilesConfig.class);

    public PolicyFilesConfig() {
        final PolicyFilesSettings settings = PolicyFilesSettings.fromEnvironment(System::getenv);
        if (settings.directories().isEmpty()) {
            LOGGER.info("POLICY_FILES_DIRECTORIES is not set, so a policy may not name files.");
        } else {
            LOGGER.info("A policy may name files in {}, of at most {} bytes.", settings.directories(), settings.maxBytes());
        }
    }

}
