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

import ai.philterd.philter.data.schema.Migration;

/** Version 2: a context's values, such as its random date shift, are kept as a JSON object in its row. */
final class SqliteContextValuesMigration implements Migration {

    private final SqliteDatabase database;

    SqliteContextValuesMigration(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "Add contexts.context_values";
    }

    @Override
    public void apply() {
        database.write(connection -> {
            // Checked first, so the migration can run again after stopping before its version was recorded.
            if (Sql.count(connection, "SELECT COUNT(*) AS n FROM pragma_table_info('contexts') WHERE name = 'context_values'") == 0) {
                Sql.update(connection, "ALTER TABLE contexts ADD COLUMN context_values TEXT");
            }
            return null;
        });
    }

}
