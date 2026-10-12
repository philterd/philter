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

import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.services.diffuse.StoredPiiCountAggregatePublisher;

import java.util.Date;
import java.util.Set;

/** Records PII counts in SQLite, one row per context and day, and one per PII type in it. */
public class SqlitePiiCountAggregatePublisher extends StoredPiiCountAggregatePublisher {

    private final SqliteDatabase database;

    public SqlitePiiCountAggregatePublisher(final SqliteDatabase database, final AdminSettingsDataService adminSettingsDataService) {
        super(adminSettingsDataService);
        this.database = database;
    }

    @Override
    protected void increment(final String context, final Date bucketStart, final Set<String> piiTypes, final Date now) {
        database.write(connection -> {
            Sql.update(connection, "INSERT INTO pii_count_aggregates (context, bucket_start, total_documents, updated_at) "
                    + "VALUES (?, ?, 1, ?) ON CONFLICT (context, bucket_start) DO UPDATE SET "
                    + "total_documents = total_documents + 1, updated_at = excluded.updated_at", context, bucketStart, now);
            for (final String piiType : piiTypes) {
                Sql.update(connection, "INSERT INTO pii_counts (context, bucket_start, pii_type, count) VALUES (?, ?, ?, 1) "
                        + "ON CONFLICT (context, bucket_start, pii_type) DO UPDATE SET count = count + 1",
                        context, bucketStart, piiType);
            }
            return null;
        });
    }

}
