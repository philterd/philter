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
package ai.philterd.philter.model;

public class Constants {

    /**
     * Maximum file size for document uploads via API and UI. Defaults to 10 MB; override with the
     * {@code MAX_FILE_SIZE_BYTES} environment variable.
     */
    public static final int MAX_FILE_SIZE_BYTES = (int) getEnvLong("MAX_FILE_SIZE_BYTES", 10L * 1024 * 1024);

    /**
     * Maximum file size in bytes for all other POST and PUT endpoints. Defaults to 10 KB; override
     * with the {@code MAX_FILE_SIZE_BYTES_OTHER} environment variable.
     */
    public static final long MAX_FILE_SIZE_BYTES_OTHER = getEnvLong("MAX_FILE_SIZE_BYTES_OTHER", 10L * 1024);

    private Constants() {

    }

    private static long getEnvLong(final String name, final long defaultValue) {

        final String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Long.parseLong(value.trim());
        } catch (final NumberFormatException ex) {
            return defaultValue;
        }

    }

}
