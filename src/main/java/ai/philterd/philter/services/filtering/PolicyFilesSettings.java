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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * Which files a policy may make Phileas read. Any user can write a policy, and a file a policy names becomes a test
 * of its contents (a dictionary) or reveals them outright ({@code MAP_REPLACE} mapping files), so Philter always
 * restricts these files to the directories in {@code POLICY_FILES_DIRECTORIES}, and to files no larger than
 * {@code POLICY_FILES_MAX_BYTES}. With no directories set, a policy may name no file.
 *
 * @param directories The allowed directories, resolved.
 * @param maxBytes The largest file a policy may name.
 */
public record PolicyFilesSettings(List<String> directories, long maxBytes) {

    /** The default of {@code POLICY_FILES_MAX_BYTES}: 10 MB. */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    /**
     * Reads and checks the settings.
     * @param environment Reads an environment variable, such as {@code System::getenv}.
     * @return The settings.
     * @throws IllegalStateException if a directory does not exist, is not a directory, or is the filesystem root, or the
     * size limit is not a positive number, so a mistake stops Philter rather than silently changing what is allowed.
     */
    public static PolicyFilesSettings fromEnvironment(final Function<String, String> environment) {

        final List<String> directories = new ArrayList<>();
        final String listed = environment.apply("POLICY_FILES_DIRECTORIES");
        if (listed != null) {
            for (final String directory : Arrays.stream(listed.split(",")).map(String::strip).filter(d -> !d.isEmpty()).toList()) {
                final Path real;
                try {
                    real = Path.of(directory).toRealPath();
                } catch (final IOException | RuntimeException ex) {
                    throw new IllegalStateException("POLICY_FILES_DIRECTORIES names '" + directory + "', which does not exist.");
                }
                if (!Files.isDirectory(real)) {
                    throw new IllegalStateException("POLICY_FILES_DIRECTORIES names '" + directory + "', which is not a directory.");
                }
                if (real.getParent() == null) {
                    throw new IllegalStateException("POLICY_FILES_DIRECTORIES cannot name the filesystem root, which would "
                            + "let a policy name any file.");
                }
                directories.add(real.toString());
            }
        }

        long maxBytes = DEFAULT_MAX_BYTES;
        final String limit = environment.apply("POLICY_FILES_MAX_BYTES");
        if (limit != null && !limit.isBlank()) {
            try {
                maxBytes = Long.parseLong(limit.strip());
            } catch (final NumberFormatException ex) {
                maxBytes = 0;
            }
            if (maxBytes <= 0) {
                throw new IllegalStateException("POLICY_FILES_MAX_BYTES must be a positive number of bytes.");
            }
        }

        return new PolicyFilesSettings(List.copyOf(directories), maxBytes);

    }

    /** Sets the Phileas properties that enforce these settings. The restriction is always on. */
    public void applyTo(final Properties properties) {
        properties.put("policy.files.restricted", "true");
        properties.put("policy.files.directories", String.join(",", directories));
        properties.put("policy.files.max.bytes", Long.toString(maxBytes));
    }

}
