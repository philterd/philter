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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.schema.SchemaMigrator;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Records past their retention are removed as Philter writes; evidence is never removed this way. */
class SqliteRetentionTest {

    @TempDir
    Path directory;

    private SqliteDatabase database;

    @BeforeEach
    void open() {
        database = new SqliteDatabase(directory.resolve("philter.db").toString());
        new SchemaMigrator(new SqliteSchemaStore(database, List.of()), List.of(), Duration.ofSeconds(5)).migrate();
    }

    @AfterEach
    void close() {
        database.close();
    }

    private long count(final String table) {
        return database.read(connection -> Sql.count(connection, "SELECT COUNT(*) AS n FROM " + table));
    }

    @Test
    void finishedWebhookDeliveriesAreRemovedAfterTheirRetention() {
        final long longAgo = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000;
        database.write(connection -> {
            Sql.update(connection, "INSERT INTO webhook_deliveries (id, user_id, status, completed_at) VALUES (?, ?, ?, ?)",
                    RecordIds.newId(), RecordIds.newId(), WebhookDeliveryEntity.STATUS_DELIVERED, new Date(longAgo));
            return Sql.update(connection, "INSERT INTO webhook_deliveries (id, user_id, status, completed_at) VALUES (?, ?, ?, ?)",
                    RecordIds.newId(), RecordIds.newId(), WebhookDeliveryEntity.STATUS_DELIVERED, new Date());
        });
        new SqliteWebhookDeliveryDataService(database, new TestEncryptionService()).claimNextDue(new Date());
        assertEquals(1, count("webhook_deliveries"), "only the recently finished delivery is kept");
    }

    @Test
    void expiredSignInChallengesAreRemoved() {
        database.write(connection -> Sql.update(connection, "INSERT INTO sign_in_challenges (token_hash, user_id, expires_at) "
                + "VALUES ('old', ?, ?)", RecordIds.newId(), new Date(System.currentTimeMillis() - 1000)));
        new SqliteSignInChallengeDataService(database).create(RecordIds.newId());
        assertEquals(1, count("sign_in_challenges"), "only the new challenge is kept");
    }

}
