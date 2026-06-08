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
 * The kill switch for admin cross-user access: whether an administrator may view or act on
 * <em>other</em> users' resources (their contexts, policies, custom lists, documents, and redaction
 * ledger). This governs both the API {@code owner} parameter and the admin "All …" UI tabs.
 *
 * <p>It does <strong>not</strong> affect ordinary admin functions such as user management, nor a
 * user's access to their own data.
 *
 * <p><strong>Disabled by default</strong> (an admin sees only their own data, like any user). Set the
 * {@code ADMIN_CROSS_USER_ACCESS_ENABLED} environment variable to {@code true} to opt in.
 */
public final class AdminAccessConfig {

    // Test-only override: when non-null it takes precedence over the environment variable. Set via
    // setOverrideForTesting and cleared (null) afterwards so production always reads the env var.
    private static volatile Boolean overrideForTesting = null;

    private AdminAccessConfig() {
    }

    public static boolean isCrossUserAccessEnabled() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        return EnvUtils.getBoolean("ADMIN_CROSS_USER_ACCESS_ENABLED", false);
    }

    /** Test hook: force the flag on/off, or pass {@code null} to fall back to the environment variable. */
    public static void setOverrideForTesting(final Boolean value) {
        overrideForTesting = value;
    }

}
