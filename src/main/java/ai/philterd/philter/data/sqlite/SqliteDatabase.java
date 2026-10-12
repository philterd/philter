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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One connection to Philter's SQLite database file. Every read and write goes through it, one at a time; a write
 * runs in a transaction that takes the database's write lock as it begins, so a read-then-write inside it is
 * atomic, also against other connections to the same file.
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

    public SqliteDatabase(final String path) {
        this.path = path;
        final SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // Committed writes survive a power loss, which evidence such as the ledger and audit log needs.
        config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLIS);
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
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
            return work.run(connection);
        } catch (final SQLException ex) {
            throw translate(ex);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs work in one transaction, holding the database's write lock from its start, and commits it, or rolls it
     * back and rethrows when it fails.
     *
     * @throws DuplicateRecordException when a uniqueness constraint refuses a write; nothing is written.
     */
    public <T> T write(final Work<T> work) {
        lock.lock();
        try {
            connection.setAutoCommit(false);
            try {
                final T result = work.run(connection);
                connection.commit();
                return result;
            } catch (final SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (final SQLException ex) {
            throw translate(ex);
        } finally {
            lock.unlock();
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
