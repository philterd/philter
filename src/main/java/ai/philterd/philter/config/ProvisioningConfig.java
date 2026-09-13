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

import ai.philterd.philter.utils.EnvUtils;

/**
 * The kill switch for the provisioning endpoints, which create users and mint an API key for a user
 * the caller is not signed in as ({@code POST /api/users} and
 * {@code POST /api/users/{username}/api-keys}). Disabled by default: with it off both endpoints
 * answer {@code 404 Not Found}, as though they were not there.
 *
 * <p>Creating a credential in the dashboard forces the caller through whatever the login requires,
 * including MFA. These endpoints are the way around that, which is why they are a property of the
 * deployment rather than a setting. An environment variable can only be changed by changing the
 * deployment's configuration and restarting it; an admin setting could be changed by anything able
 * to write the database.
 */
public final class ProvisioningConfig {

    // Test-only override: when non-null it takes precedence over the environment variable. Set via
    // setOverrideForTesting and cleared (null) afterwards so production always reads the env var.
    private static volatile Boolean overrideForTesting = null;

    private ProvisioningConfig() {
    }

    public static boolean isProvisioningApiEnabled() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        return EnvUtils.getBoolean("PROVISIONING_API_ENABLED", false);
    }

    /** Test hook: force the flag on/off, or pass {@code null} to fall back to the environment variable. */
    public static void setOverrideForTesting(final Boolean value) {
        overrideForTesting = value;
    }

}
