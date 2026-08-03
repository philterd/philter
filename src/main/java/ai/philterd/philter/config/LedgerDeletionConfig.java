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
 * The kill switch for redaction-ledger deletion, governing the {@code DELETE /api/ledger} endpoints
 * and the equivalent dashboard actions. Deletion is additionally restricted to administrators.
 *
 * <p>Separate from {@link AdminAccessConfig} on purpose: that controls <em>whose</em> data an admin
 * may reach, this controls whether evidence may be <em>destroyed</em>. Deleting another user's ledger
 * requires both. Disabled by default.
 */
public final class LedgerDeletionConfig {

    // Test-only override: when non-null it takes precedence over the environment variable. Set via
    // setOverrideForTesting and cleared (null) afterwards so production always reads the env var.
    private static volatile Boolean overrideForTesting = null;

    private LedgerDeletionConfig() {
    }

    public static boolean isLedgerDeletionEnabled() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        return EnvUtils.getBoolean("LEDGER_DELETION_ENABLED", false);
    }

    /** Test hook: force the flag on/off, or pass {@code null} to fall back to the environment variable. */
    public static void setOverrideForTesting(final Boolean value) {
        overrideForTesting = value;
    }

}
