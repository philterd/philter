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

import ai.philterd.philter.data.services.DuplicateRecordException;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One connection to Philter's SQLite database file. Every read and write goes through it, one at a time; a write
 * runs in a transaction that takes the database's write lock as it begins, so a read-then-write inside it is
 * atomic, also against other connections to the same file. The file is readable and writable only by the user
 * Philter runs as.
 */
public final class SqliteDatabase implements AutoCloseable {

    /** How long a write waits for another connection's write to finish. */
    private static final int BUSY_TIMEOUT_MILLIS = 30_000;

    /** Work done with the connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private final String path;
    private final Connection connection;
    private final ReentrantLock lock = new ReentrantLock();

    /** Why the connection was closed, when a write could not be rolled back. */
    private volatile Exception broken;

    public SqliteDatabase(final String path) {
        this.path = path;
        final SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // Committed writes survive a power loss, which evidence such as the ledger and audit log needs.
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        OwnerOnlyFiles.prepare(Path.of(path));
        OwnerOnlyFiles.restrict(Path.of(path + "-wal"));
        OwnerOnlyFiles.restrict(Path.of(path + "-shm"));
        try {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
        } catch (final SQLException ex) {
            throw new IllegalStateException("Unable to open the SQLite database at " + path + ": " + ex.getMessage(), ex);
        }
    }

    /** The database file. */
    public String path() {
        return path;
    }

    /** Runs a read. */
    public <T> T read(final Work<T> work) {
        lock.lock();
        try {
            requireUsable();
            return work.run(connection);
        } catch (final SQLException ex) {
            throw translate(ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs work in one transaction, holding the database's write lock from its start, and commits it, or rolls it
     * back and rethrows when anything fails, so a write is all or nothing.
     *
     * @throws DuplicateRecordException when a uniqueness constraint refuses a write; nothing is written.
     */
    public <T> T write(final Work<T> work) {
        lock.lock();
        try {
            requireUsable();
            connection.setAutoCommit(false);
            boolean committed = false;
            try {
                final T result = work.run(connection);
                connection.commit();
                committed = true;
                return result;
            } finally {
                // Whatever went wrong, including an Error, the transaction is rolled back, never left open.
                if (committed) {
                    connection.setAutoCommit(true);
                } else {
                    abandon();
                }
            }
        } catch (final SQLException ex) {
            throw translate(ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Rolls back the open transaction. Turning auto-commit back on commits whatever is open, so that waits until the
     * rollback succeeded. When it fails, the connection is closed, which discards the transaction, and the database
     * refuses further work until Philter restarts, rather than risk committing half of a write.
     */
    private void abandon() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (final SQLException | RuntimeException ex) {
            broken = ex;
            try {
                connection.close();
            } catch (final SQLException closing) {
                ex.addSuppressed(closing);
            }
        }
    }

    private void requireUsable() {
        if (broken != null) {
            throw new IllegalStateException("The SQLite database was closed after a write could not be rolled back. "
                    + "Restart Philter.", broken);
        }
    }

    private static RuntimeException translate(final SQLException ex) {
        if (ex instanceof SQLiteException sqlite && (sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE
                || sqlite.getResultCode() == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY)) {
            return new DuplicateRecordException(ex);
        }
        return new IllegalStateException("SQLite storage failed: " + ex.getMessage(), ex);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            connection.close();
        } catch (final SQLException ex) {
            throw translate(ex);
        } finally {
            lock.unlock();
        }
    }

}
