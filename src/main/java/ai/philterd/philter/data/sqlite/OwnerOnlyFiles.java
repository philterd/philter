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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Keeps Philter's SQLite files readable and writable only by the user Philter runs as, where the file system has
 * POSIX permissions. The database holds password hashes, the audit log, and other records no other local user should
 * read. SQLite gives the files it creates beside the database (its write-ahead log and shared memory) the database
 * file's permissions.
 */
final class OwnerOnlyFiles {

    private static final Logger LOGGER = LogManager.getLogger(OwnerOnlyFiles.class);

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private OwnerOnlyFiles() {
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    /** The attributes that create a file owner-only, or none where the file system has no POSIX permissions. */
    static FileAttribute<?>[] attributes() {
        return posix() ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY)} : new FileAttribute<?>[0];
    }

    /** Creates the file owner-only when it does not exist, and narrows it to owner-only when it does. */
    static void prepare(final Path file) {
        if (!posix()) {
            return;
        }
        try {
            Files.createFile(file, attributes());
        } catch (final FileAlreadyExistsException exists) {
            restrict(file);
        } catch (final IOException ex) {
            throw new IllegalStateException("Unable to create " + file + ": " + ex.getMessage(), ex);
        }
    }

    /** Narrows an existing file to owner-only, warning when it cannot. */
    static void restrict(final Path file) {
        if (!posix() || !Files.exists(file)) {
            return;
        }
        try {
            if (!OWNER_ONLY.equals(Files.getPosixFilePermissions(file))) {
                Files.setPosixFilePermissions(file, OWNER_ONLY);
                LOGGER.info("Restricted {} to the user Philter runs as.", file);
            }
        } catch (final IOException | UnsupportedOperationException ex) {
            LOGGER.warn("Unable to restrict {} to the user Philter runs as; other local users may be able to read it.",
                    file, ex);
        }
    }

}
