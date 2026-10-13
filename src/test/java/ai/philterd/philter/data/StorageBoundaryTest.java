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
 * of Philter. Every other class reaches storage through the interfaces in {@code data.services}. The one exception is
 * {@code data.migration}, which moves data from one storage to another and so uses both implementations, though
 * never their drivers.
 */
class StorageBoundaryTest {

    private static final Path SOURCES = Path.of("src/main/java/ai/philterd/philter");
    private static final Path MIGRATION = SOURCES.resolve("data/migration");
    private static final Path MONGODB = SOURCES.resolve("data/mongodb");
    private static final Pattern MONGODB_DRIVER =
            Pattern.compile("^import (static )?(com\\.mongodb|org\\.bson)\\.", Pattern.MULTILINE);
    private static final Pattern MONGODB_STORAGE =
            Pattern.compile("^import (static )?ai\\.philterd\\.philter\\.data\\.mongodb\\.", Pattern.MULTILINE);
    private static final Path SQLITE = SOURCES.resolve("data/sqlite");
    private static final Pattern SQLITE_DRIVER =
            Pattern.compile("^import (static )?(org\\.sqlite|java\\.sql)\\.", Pattern.MULTILINE);
    private static final Pattern SQLITE_STORAGE =
            Pattern.compile("^import (static )?ai\\.philterd\\.philter\\.data\\.sqlite\\.", Pattern.MULTILINE);

    /** The classes outside each of {@code allowed} that import what {@code imports} matches. */
    private static List<String> importersOutside(final Pattern imports, final Path... allowed) throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (final Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (Stream.of(allowed).noneMatch(file::startsWith) && imports.matcher(Files.readString(file)).find()) {
                    offenders.add(SOURCES.relativize(file).toString());
                }
            }
        }
        return offenders;
    }

    @Test
    void noClassOutsideTheStorageImplementationUsesMongoDb() throws IOException {
        assertEquals(List.of(), importersOutside(MONGODB_DRIVER, MONGODB), "these classes use the MongoDB driver "
                + "directly; reach storage through the interfaces in data.services instead");
        assertEquals(List.of(), importersOutside(MONGODB_STORAGE, MONGODB, MIGRATION), "these classes use the MongoDB "
                + "storage directly; reach storage through the interfaces in data.services instead");
    }

    @Test
    void noClassOutsideTheStorageImplementationUsesSqlite() throws IOException {
        assertEquals(List.of(), importersOutside(SQLITE_DRIVER, SQLITE), "these classes use SQLite or JDBC directly; "
                + "reach storage through the interfaces in data.services instead");
        assertEquals(List.of(), importersOutside(SQLITE_STORAGE, SQLITE, MIGRATION), "these classes use the SQLite "
                + "storage directly; reach storage through the interfaces in data.services instead");
    }

}
