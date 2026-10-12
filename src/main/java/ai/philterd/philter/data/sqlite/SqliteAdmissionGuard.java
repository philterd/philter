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

import ai.philterd.philter.data.services.AdmissionGuard;

import java.util.Date;

/** The queue admission guard in SQLite: one row holding the token of the admission that holds it. */
final class SqliteAdmissionGuard implements AdmissionGuard {

    private final SqliteDatabase database;

    SqliteAdmissionGuard(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public boolean tryAcquire(final String token) {
        return database.write(connection -> {
            Sql.update(connection, "INSERT INTO queue_admission (name) VALUES ('queue') ON CONFLICT (name) DO NOTHING");
            return Sql.update(connection, "UPDATE queue_admission SET token = ?, started_at = ? WHERE name = 'queue' "
                    + "AND token IS NULL", token, new Date());
        }) == 1;
    }

    @Override
    public boolean release(final String token) {
        return database.write(connection -> Sql.update(connection, "UPDATE queue_admission SET token = NULL, started_at = NULL "
                + "WHERE name = 'queue' AND token = ?", token)) == 1;
    }

}
