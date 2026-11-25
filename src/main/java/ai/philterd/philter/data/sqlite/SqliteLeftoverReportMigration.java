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

/**
 * Version 3: a queued document keeps whether its request asked for the leftover check, and what its redaction
 * reported, the X-Philter-Leftovers and X-Philter-Email-Report values returned on its status and download.
 */
final class SqliteLeftoverReportMigration implements Migration {

    private final SqliteDatabase database;

    SqliteLeftoverReportMigration(final SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "Add pending_documents.leftover_check_requested, leftovers, and email_report";
    }

    @Override
    public void apply() {
        database.write(connection -> {
            // Each is checked first, so the migration can run again after stopping before its version was recorded.
            add(connection, "leftover_check_requested", "INTEGER NOT NULL DEFAULT 0");
            add(connection, "leftovers", "TEXT");
            add(connection, "email_report", "TEXT");
            return null;
        });
    }

    private static void add(final java.sql.Connection connection, final String column, final String type)
            throws java.sql.SQLException {
        if (Sql.count(connection, "SELECT COUNT(*) AS n FROM pragma_table_info('pending_documents') WHERE name = '"
                + column + "'") == 0) {
            Sql.update(connection, "ALTER TABLE pending_documents ADD COLUMN " + column + " " + type);
        }
    }

}
