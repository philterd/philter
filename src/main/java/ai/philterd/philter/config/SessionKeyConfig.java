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
 * How long a session key, the key issued when a person signs in, stays valid: an idle timeout, reset by
 * each request, and a maximum lifetime that no activity extends. Each key records both when it is
 * issued, so a change applies to keys issued afterwards.
 */
public final class SessionKeyConfig {

    public static final int DEFAULT_IDLE_TIMEOUT_MINUTES = 30;
    public static final int DEFAULT_MAX_LIFETIME_MINUTES = 720;

    private SessionKeyConfig() {
    }

    public static int idleTimeoutMinutes() {
        return positive("SESSION_KEY_IDLE_TIMEOUT_MINUTES", DEFAULT_IDLE_TIMEOUT_MINUTES);
    }

    public static int maxLifetimeMinutes() {
        return positive("SESSION_KEY_MAX_LIFETIME_MINUTES", DEFAULT_MAX_LIFETIME_MINUTES);
    }

    /**
     * Fails startup on zero or a negative value, rather than at first sign-in. A value that is not a
     * whole number falls back to the default with a warning, as every setting read through
     * {@link EnvUtils} does.
     */
    public static void validate() {
        idleTimeoutMinutes();
        maxLifetimeMinutes();
    }

    private static int positive(final String name, final int defaultValue) {
        final int value = EnvUtils.getInt(name, defaultValue);
        if (value <= 0) {
            throw new IllegalStateException(name + " must be a positive number of minutes.");
        }
        return value;
    }

}
