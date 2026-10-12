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

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/**
 * Keeps a second Philter from using the same SQLite database. SQLite storage is for one instance: the guarantees
 * other instances would rely on, such as one active signing key or exactly-once expiry, are kept only within it.
 * The lock is an exclusive lock on a file beside the database, held while Philter runs and released by the
 * operating system if it stops.
 */
public final class SqliteInstanceLock implements AutoCloseable {

    private final FileChannel channel;
    private final FileLock lock;

    private SqliteInstanceLock(final FileChannel channel, final FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Takes the lock for the database at {@code databasePath}.
     *
     * @throws IllegalStateException when another Philter holds it.
     */
    public static SqliteInstanceLock acquire(final String databasePath) {
        final Path lockFile = Path.of(databasePath + ".lock");
        try {
            final FileChannel channel = FileChannel.open(lockFile, Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                    OwnerOnlyFiles.attributes());
            OwnerOnlyFiles.restrict(lockFile);
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (final OverlappingFileLockException heldHere) {
                channel.close();
                throw inUse(databasePath);
            }
            if (lock == null) {
                channel.close();
                throw inUse(databasePath);
            }
            return new SqliteInstanceLock(channel, lock);
        } catch (final IOException ex) {
            throw new IllegalStateException("Unable to lock the SQLite database at " + databasePath + ": " + ex.getMessage(), ex);
        }
    }

    private static IllegalStateException inUse(final String databasePath) {
        return new IllegalStateException("Another Philter is using the SQLite database at " + databasePath + ". SQLite "
                + "storage supports one Philter instance; stop the other one, or use MongoDB to run several.");
    }

    @Override
    public void close() throws IOException {
        lock.release();
        channel.close();
    }

}
