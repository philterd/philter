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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.config.StorageConfiguration;
import ai.philterd.philter.data.schema.SchemaMigrator;
import ai.philterd.philter.utils.EnvUtils;
import com.mongodb.client.MongoClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The MongoDB connection, and bringing the data to the schema version this release expects before any service
 * uses it ({@link MongoStorageConfiguration} depends on {@link #SCHEMA_VERSION}).
 */
@Configuration
@ConditionalOnProperty(name = StorageConfiguration.PROPERTY, havingValue = StorageConfiguration.MONGODB, matchIfMissing = true)
public class MongoConnectionConfiguration {

    /** The name of the bean that migrates the schema, which the storage beans wait for. */
    public static final String SCHEMA_VERSION = "schemaVersion";

    /** How long an instance waits for another to finish migrating before it gives up. */
    static final long SCHEMA_MIGRATION_WAIT_SECONDS = EnvUtils.getLong("SCHEMA_MIGRATION_WAIT_SECONDS", 600L);

    /** The version the data is at once Philter has started. */
    public record SchemaVersion(int version) {
    }

    @Bean
    public MongoClient mongoClient() {
        return MongoClientUtil.getClient();
    }

    @Bean(name = SCHEMA_VERSION)
    public SchemaVersion schemaVersion(final MongoClient mongoClient) {
        return new SchemaVersion(new SchemaMigrator(new MongoSchemaStore(mongoClient), MongoMigrations.all(mongoClient),
                Duration.ofSeconds(SCHEMA_MIGRATION_WAIT_SECONDS)).migrate());
    }

}
