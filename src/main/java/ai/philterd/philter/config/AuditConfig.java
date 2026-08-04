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
 * Whether per-redaction audit events are recorded. These are the only audit events on the hot path,
 * two per redaction, and the only unbounded source of growth in the audit log, so a deployment
 * running Philter as a plain redaction engine can switch them off.
 *
 * <p>Security events are never affected: authentication, account, key, policy, evidence-access and
 * legal-hold events are always recorded, whatever this is set to. Turning off the whole audit log is
 * not offered, because that is a compliance decision rather than a tuning knob.
 *
 * <p>Enabled by default.
 */
public final class AuditConfig {

    private static volatile Boolean overrideForTesting = null;

    private AuditConfig() {
    }

    public static boolean isRedactionAuditingEnabled() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        return EnvUtils.getBoolean("AUDIT_REDACTION_EVENTS_ENABLED", true);
    }

    public static void setOverrideForTesting(final Boolean value) {
        overrideForTesting = value;
    }

}
