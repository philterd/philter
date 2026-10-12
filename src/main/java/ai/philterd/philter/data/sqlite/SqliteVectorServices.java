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

import ai.philterd.philter.data.entities.VectorEntity;
import ai.philterd.philter.services.vectors.StoredVectorServices;

import java.util.List;

/** Stores disambiguation vectors in SQLite, one row per hash. */
public class SqliteVectorServices extends StoredVectorServices {

    private final SqliteDatabase database;

    public SqliteVectorServices(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    protected void insert(final VectorEntity vector) {
        database.write(connection -> Sql.update(connection,
                "INSERT INTO vectors (user_id, context, hash, vector_size, filter_type) VALUES (?, ?, ?, ?, ?)",
                vector.getUserId(), vector.getContext(), vector.getHash(), vector.getVectorSize(), vector.getFilterType()));
    }

    @Override
    protected long count(final String userId, final String context) {
        return database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM vectors WHERE user_id = ? AND context = ?", userId, context));
    }

    @Override
    protected void deleteOldest(final String userId, final String context) {
        database.write(connection -> Sql.update(connection, "DELETE FROM vectors WHERE seq = "
                + "(SELECT MIN(seq) FROM vectors WHERE user_id = ? AND context = ?)", userId, context));
    }

    @Override
    protected List<Double> findHashes(final String userId, final String context, final String filterType) {
        return database.read(connection -> Sql.query(connection,
                "SELECT hash FROM vectors WHERE user_id = ? AND context = ? AND filter_type = ?",
                row -> row.doubleValue("hash"), userId, context, filterType));
    }

    @Override
    public void deleteByContext(final String userId, final String context) {
        database.write(connection -> Sql.update(connection, "DELETE FROM vectors WHERE user_id = ? AND context = ?",
                userId, context));
    }

}
