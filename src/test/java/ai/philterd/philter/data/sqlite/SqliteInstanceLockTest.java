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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteInstanceLockTest {

    @TempDir
    Path directory;

    @Test
    void aSecondPhilterOnTheSameDatabaseIsRefused() throws Exception {
        final String path = directory.resolve("philter.db").toString();
        try (SqliteInstanceLock first = SqliteInstanceLock.acquire(path)) {
            final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> SqliteInstanceLock.acquire(path));
            assertTrue(refused.getMessage().contains("Another Philter is using the SQLite database"), refused.getMessage());
            // Another database is unaffected.
            assertDoesNotThrow(() -> SqliteInstanceLock.acquire(directory.resolve("other.db").toString()).close());
        }
        // Once the first stops, the database can be used again.
        assertDoesNotThrow(() -> SqliteInstanceLock.acquire(path).close());
    }

    @Test
    void aSecondPhilterProcessIsRefused() throws Exception {
        final String path = directory.resolve("philter.db").toString();
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final Process holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SqliteLockHolder.class.getName(), path).redirectErrorStream(true).start();
        try {
            final BufferedReader output = new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8));
            // Logging may write first; the holder prints "locked" once it holds the lock.
            String line;
            do {
                line = output.readLine();
            } while (line != null && !line.equals("locked"));
            assertEquals("locked", line, "the other process holds the lock");

            final IllegalStateException refused = assertThrows(IllegalStateException.class, () -> SqliteInstanceLock.acquire(path));
            assertTrue(refused.getMessage().contains("Another Philter is using the SQLite database"), refused.getMessage());
        } finally {
            holder.getOutputStream().close();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS));
        }
        // The operating system released the lock with the process.
        assertDoesNotThrow(() -> SqliteInstanceLock.acquire(path).close());
        assertTrue(new File(path + ".lock").exists());
    }

}
