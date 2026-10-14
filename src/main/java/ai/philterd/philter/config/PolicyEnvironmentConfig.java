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

import ai.philterd.philter.services.filtering.PolicyEnvironmentSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Checks {@code POLICY_ALLOWED_ENV_KEYS} when Philter starts and restricts the environment variables a policy may name
 * to it, so a mistake stops Philter instead of silently allowing or refusing them.
 */
@Configuration
public class PolicyEnvironmentConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyEnvironmentConfig.class);

    public PolicyEnvironmentConfig() {
        PolicyEnvironmentSettings.refuseDirectPhileasSetting(System::getenv, System::getProperty);
        final PolicyEnvironmentSettings settings = PolicyEnvironmentSettings.fromEnvironment(System::getenv);
        settings.apply();
        if (settings.names().isEmpty()) {
            LOGGER.info("POLICY_ALLOWED_ENV_KEYS is not set, so a policy may not name an environment variable.");
        } else {
            LOGGER.info("A policy may name the environment variables {}.", settings.names());
        }
    }

}
