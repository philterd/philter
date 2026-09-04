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
 * Whether Philter's outbound HTTPS calls from the redaction pipeline skip certificate and hostname
 * verification. This exists for a self-signed sidecar on a private network, such as a ph-eye instance
 * behind its own certificate.
 *
 * <p><strong>Disabled by default.</strong> With it on, any certificate from any host is accepted, so
 * the connection is interceptable by anything on the path. Prefer trusting the certificate's issuer in
 * the JVM truststore.
 */
public final class TlsVerificationConfig {

    // Test-only override: when non-null it takes precedence over the environment variable.
    private static volatile Boolean overrideForTesting = null;

    private TlsVerificationConfig() {
    }

    public static boolean isTrustAllEnabled() {
        if (overrideForTesting != null) {
            return overrideForTesting;
        }
        return EnvUtils.getBoolean("TLS_TRUST_ALL_ENABLED", false);
    }

    /** Test hook: force the flag on/off, or pass {@code null} to fall back to the environment variable. */
    public static void setOverrideForTesting(final Boolean value) {
        overrideForTesting = value;
    }

}
