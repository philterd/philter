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

import ai.philterd.philter.data.services.StoredSignInChallengeDataService;

import java.util.Date;

/** Stores sign-in challenges in SQLite, removing expired ones as challenges are made. */
public class SqliteSignInChallengeDataService extends StoredSignInChallengeDataService {

    private final SqliteDatabase database;

    public SqliteSignInChallengeDataService(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    protected void insert(final String tokenHash, final String userId, final Date expiresAt) {
        database.write(connection -> {
            Sql.update(connection, "DELETE FROM sign_in_challenges WHERE expires_at <= ?", new Date());
            return Sql.update(connection, "INSERT INTO sign_in_challenges (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
                    tokenHash, userId, expiresAt);
        });
    }

    @Override
    protected String remove(final String tokenHash, final Date now) {
        return database.write(connection -> {
            final String userId = Sql.first(connection,
                    "SELECT user_id FROM sign_in_challenges WHERE token_hash = ? AND expires_at > ?",
                    row -> row.string("user_id"), tokenHash, now);
            Sql.update(connection, "DELETE FROM sign_in_challenges WHERE token_hash = ?", tokenHash);
            return userId;
        });
    }

}
