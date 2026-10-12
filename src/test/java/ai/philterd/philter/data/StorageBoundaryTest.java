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
package ai.philterd.philter.data;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Only the storage implementation uses MongoDB, so another implementation can be added without changing the rest
 * of Philter. Every other class reaches storage through the interfaces in {@code data.services}.
 */
class StorageBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/ai/philterd/philter");
    private static final Path STORAGE = SOURCES.resolve("data/mongodb");
    private static final Pattern STORAGE_IMPORT =
            Pattern.compile("^import (static )?(com\\.mongodb|org\\.bson|ai\\.philterd\\.philter\\.data\\.mongodb)\\.", Pattern.MULTILINE);

    @Test
    void noClassOutsideTheStorageImplementationUsesMongoDb() throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (!file.startsWith(STORAGE) && STORAGE_IMPORT.matcher(Files.readString(file)).find()) {
                    offenders.add(SOURCES.relativize(file).toString());
                }
            }
        }
        assertEquals(List.of(), offenders, "these classes use MongoDB or the MongoDB storage directly; reach storage "
                + "through the interfaces in data.services instead");
    }

}
