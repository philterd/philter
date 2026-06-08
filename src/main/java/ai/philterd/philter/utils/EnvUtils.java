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
package ai.philterd.philter.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers for reading numeric configuration from environment variables without crashing startup on a
 * malformed value. A blank or unparseable value falls back to the supplied default and is logged.
 */
public final class EnvUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(EnvUtils.class);

    private EnvUtils() {
    }

    /**
     * Returns the named environment variable parsed as an int, or {@code defaultValue} if it is unset,
     * blank, or not a valid integer.
     */
    public static int getInt(final String name, final int defaultValue) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Environment variable {} value '{}' is not a valid integer; using default {}.", name, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Returns the named environment variable parsed as a boolean ({@code true}/{@code false},
     * case-insensitive), or {@code defaultValue} if it is unset or blank.
     */
    public static boolean getBoolean(final String name, final boolean defaultValue) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Returns the named environment variable parsed as a long, or {@code defaultValue} if it is unset,
     * blank, or not a valid long.
     */
    public static long getLong(final String name, final long defaultValue) {
        final String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Environment variable {} value '{}' is not a valid number; using default {}.", name, value, defaultValue);
            return defaultValue;
        }
    }

}
