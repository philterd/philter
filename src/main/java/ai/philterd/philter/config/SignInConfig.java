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
 * Whether people may sign in with a username and password ({@code POST /api/sign-in}). Disabled by
 * default, so a deployment that runs no user interface exposes no unauthenticated login endpoint. An
 * environment variable rather than an admin setting, so an administrator API key cannot open it.
 */
public final class SignInConfig {

    // Test-only override: when non-null it takes precedence over the environment variable.
    private static volatile Boolean overrideForTesting = null;

    private SignInConfig() {
    }

    public static boolean isPasswordSignInEnabled() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        return EnvUtils.getBoolean("PASSWORD_SIGN_IN_ENABLED", false);
    }

    /** Test hook: force the flag on/off, or pass {@code null} to fall back to the environment variable. */
    public static void setOverrideForTesting(final Boolean value) {
        overrideForTesting = value;
    }

}
