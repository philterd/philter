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

import ai.philterd.philter.config.StorageConfiguration;
import ai.philterd.philter.data.schema.SchemaMigrator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * The SQLite database file, held by this instance alone, and bringing it to the schema version this release
 * expects before any service uses it ({@link SqliteStorageConfiguration} depends on {@link #SCHEMA_VERSION}).
 */
@Configuration
@ConditionalOnProperty(name = StorageConfiguration.PROPERTY, havingValue = StorageConfiguration.SQLITE, matchIfMissing = true)
public class SqliteConnectionConfiguration {

    private static final Logger LOGGER = LogManager.getLogger(SqliteConnectionConfiguration.class);

    /** The name of the bean that migrates the schema, which the storage beans wait for. */
    public static final String SCHEMA_VERSION = "schemaVersion";

    /** The property {@code SQLITE_PATH} sets: the database file. */
    public static final String PATH_PROPERTY = "philter.sqlite.path";

    private final String path;

    public SqliteConnectionConfiguration(@Value("${" + PATH_PROPERTY + ":philter.db}") final String path) {
        this.path = path;
    }

    /** The version the data is at once Philter has started. */
    public record SchemaVersion(int version) {
    }

    @Bean(destroyMethod = "close")
    public SqliteInstanceLock sqliteInstanceLock() {
        return SqliteInstanceLock.acquire(path);
    }

    @Bean(destroyMethod = "close")
    public SqliteDatabase sqliteDatabase(final SqliteInstanceLock sqliteInstanceLock) {
        // Says where the data is, since a relative path, such as the default, depends on where Philter was started.
        final Path file = Path.of(path);
        if (file.isAbsolute()) {
            LOGGER.info("SQLite storage keeps Philter's data in {}.", file);
        } else {
            LOGGER.warn("SQLite storage keeps Philter's data in {}, relative to the directory Philter was started in. "
                    + "Set SQLITE_PATH to an absolute path so the data stays in one place.", file.toAbsolutePath());
        }
        return new SqliteDatabase(path);
    }

    /**
     * Brings the database to the expected schema version, then clears any guard a Philter that stopped left held.
     * Every storage bean waits for this one, so no service can take a guard before the recovery.
     */
    @Bean(name = SCHEMA_VERSION)
    public SchemaVersion schemaVersion(final SqliteDatabase sqliteDatabase) {
        final SchemaVersion version = new SchemaVersion(new SchemaMigrator(new SqliteSchemaStore(sqliteDatabase,
                SqliteMigrations.all(sqliteDatabase)), SqliteMigrations.all(sqliteDatabase), Duration.ofSeconds(60)).migrate());
        SqliteGuardRecovery.recover(sqliteDatabase, new SqliteAuditEventPublisher(sqliteDatabase));
        return version;
    }

}
