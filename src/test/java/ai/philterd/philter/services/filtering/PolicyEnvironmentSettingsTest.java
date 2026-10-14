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

import ai.philterd.phileas.policy.InvalidPolicyEnvironmentException;
import ai.philterd.phileas.policy.PolicyEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyEnvironmentSettingsTest {

    private static PolicyEnvironmentSettings settings(final Map<String, String> environment) {
        return PolicyEnvironmentSettings.fromEnvironment(environment::get);
    }

    @AfterEach
    void restore() {
        // The restriction is process-wide; leave it as Phileas starts out for the tests that follow. Philter restricts
        // it again whenever its application context starts.
        PolicyEnvironment.unrestricted();
    }

    @Test
    void unsetAllowsNoVariable() {
        final PolicyEnvironmentSettings settings = settings(Map.of());
        assertEquals(List.of(), settings.names());
        settings.apply();
        assertFalse(PolicyEnvironment.isAllowed("PATH"));
        assertThrows(InvalidPolicyEnvironmentException.class, () -> PolicyEnvironment.resolve("crypto key", "env:PATH"));
    }

    @Test
    void listedVariablesAreAllowedAndNoOthers() {
        final PolicyEnvironmentSettings settings = settings(Map.of("POLICY_ALLOWED_ENV_KEYS", " CRYPTO_KEY , FPE_KEY,,CRYPTO_KEY "));
        assertEquals(List.of("CRYPTO_KEY", "FPE_KEY"), settings.names());
        settings.apply();
        assertTrue(PolicyEnvironment.isAllowed("CRYPTO_KEY"));
        assertTrue(PolicyEnvironment.isAllowed("FPE_KEY"));
        assertFalse(PolicyEnvironment.isAllowed("PATH"));
        // A literal value is not a variable, so it is unaffected.
        assertEquals("AABB", PolicyEnvironment.resolve("crypto key", "AABB"));
    }

    @Test
    void anInvalidNameStopsPhilter() {
        for (final String name : List.of("1KEY", "KEY-NAME", "KEY NAME", "env:KEY")) {
            final String message = assertThrows(IllegalStateException.class,
                    () -> settings(Map.of("POLICY_ALLOWED_ENV_KEYS", name))).getMessage();
            assertTrue(message.contains("not an environment variable name"), message);
        }
    }

    @Test
    void philtersOwnSecretsCannotBeAllowed() {
        for (final String secret : PolicyEnvironmentSettings.PHILTER_SECRETS) {
            final String message = assertThrows(IllegalStateException.class,
                    () -> settings(Map.of("POLICY_ALLOWED_ENV_KEYS", "CRYPTO_KEY," + secret))).getMessage();
            assertTrue(message.contains(secret) && message.contains("Philter secret"), message);
        }
    }

    @Test
    void theDirectPhileasSettingIsRefused() {
        PolicyEnvironmentSettings.refuseDirectPhileasSetting(name -> null, name -> null);
        final String property = PolicyEnvironmentSettings.PHILEAS_PROPERTY;
        assertThrows(IllegalStateException.class, () -> PolicyEnvironmentSettings.refuseDirectPhileasSetting(
                name -> property.equals(name) ? "PATH" : null, name -> null));
        // Even empty: Phileas would read it as "no variable", but it still bypasses POLICY_ALLOWED_ENV_KEYS.
        assertThrows(IllegalStateException.class, () -> PolicyEnvironmentSettings.refuseDirectPhileasSetting(
                name -> null, name -> property.equals(name) ? "" : null));
    }

}
