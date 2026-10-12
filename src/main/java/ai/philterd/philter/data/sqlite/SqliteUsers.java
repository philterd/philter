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

/** Reads of the users table that services other than the user service need. */
final class SqliteUsers {

    private SqliteUsers() {
    }

    /** Whether the user exists, is an administrator, and is not deactivated. */
    static boolean isActiveAdministrator(final SqliteDatabase database, final String userId) {
        return userId != null && database.read(connection -> Sql.count(connection,
                "SELECT COUNT(*) AS n FROM users WHERE id = ? AND lower(role) = 'admin' AND deactivated = 0", userId) > 0);
    }

}
