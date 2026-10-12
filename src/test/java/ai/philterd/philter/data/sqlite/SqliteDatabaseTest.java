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
package ai.philterd.philter.data.sqlite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** A write is all or nothing, however it fails, and only the user Philter runs as can read the files. */
class SqliteDatabaseTest {

    @TempDir
    Path directory;

    private SqliteDatabase database;

    @BeforeEach
    void open() {
        database = new SqliteDatabase(directory.resolve("philter.db").toString());
        database.write(connection -> Sql.update(connection, "CREATE TABLE t (x INTEGER)"));
    }

    @AfterEach
    void close() {
        database.close();
    }

    private long rows() {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM t"));
    }

    @Test
    @DisplayName("A write that throws an exception after changing rows changes nothing")
    void anExceptionRollsBack() {
        assertThrows(IllegalStateException.class, () -> database.write(connection -> {
            Sql.update(connection, "INSERT INTO t (x) VALUES (1)");
            throw new IllegalStateException("failed");
        }));
        assertEquals(0, rows());
    }

    @Test
    @DisplayName("A write that throws an Error after changing rows changes nothing")
    void anErrorRollsBack() {
        assertThrows(AssertionError.class, () -> database.write(connection -> {
            Sql.update(connection, "INSERT INTO t (x) VALUES (1)");
            throw new AssertionError("failed");
        }));
        assertEquals(0, rows());
        database.write(connection -> Sql.update(connection, "INSERT INTO t (x) VALUES (2)"));
        assertEquals(1, rows(), "the database still takes writes");
    }

    @Test
    @DisplayName("The database, its write-ahead log, and its lock file are readable only by the user Philter runs as")
    void theFilesAreOwnerOnly() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        final Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
        database.write(connection -> Sql.update(connection, "INSERT INTO t (x) VALUES (1)"));

        assertEquals(ownerOnly, Files.getPosixFilePermissions(directory.resolve("philter.db")));
        assertEquals(ownerOnly, Files.getPosixFilePermissions(directory.resolve("philter.db-wal")));
        try (SqliteInstanceLock ignored = SqliteInstanceLock.acquire(directory.resolve("philter.db").toString())) {
            assertEquals(ownerOnly, Files.getPosixFilePermissions(directory.resolve("philter.db.lock")));
        }
    }

    @Test
    @DisplayName("An existing database that others can read is narrowed to the user Philter runs as when it opens")
    void anExistingDatabaseIsNarrowed() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        final Path file = directory.resolve("existing.db");
        Files.createFile(file);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        new SqliteDatabase(file.toString()).close();

        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
    }

}
