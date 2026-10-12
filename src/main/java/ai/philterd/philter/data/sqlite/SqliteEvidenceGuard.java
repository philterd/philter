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

import ai.philterd.philter.data.services.EvidenceGuard;

import java.util.Date;
import java.util.UUID;

/** The evidence guard in SQLite: a row per owner holding the token of the operation that holds it. */
public final class SqliteEvidenceGuard implements EvidenceGuard {

    private final SqliteDatabase database;

    public SqliteEvidenceGuard(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public String acquire(final String owner, final String operation) {
        final String token = UUID.randomUUID().toString();
        final int taken = database.write(connection -> {
            Sql.update(connection, "INSERT INTO evidence_guards (owner) VALUES (?) ON CONFLICT (owner) DO NOTHING", owner);
            return Sql.update(connection, "UPDATE evidence_guards SET token = ?, operation = ?, started_at = ? "
                    + "WHERE owner = ? AND token IS NULL", token, operation, new Date(), owner);
        });
        return taken == 1 ? token : null;
    }

    @Override
    public boolean release(final String owner, final String token) {
        return database.write(connection -> Sql.update(connection,
                "UPDATE evidence_guards SET token = NULL, operation = NULL, started_at = NULL WHERE owner = ? AND token = ?",
                owner, token)) == 1;
    }

}
