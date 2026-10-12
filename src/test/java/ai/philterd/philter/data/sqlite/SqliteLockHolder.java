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

/** Holds the SQLite instance lock in its own process, as another Philter would, until its input closes. */
public final class SqliteLockHolder {

    private SqliteLockHolder() {
    }

    public static void main(final String[] args) throws Exception {
        try (SqliteInstanceLock lock = SqliteInstanceLock.acquire(args[0])) {
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() != -1) {
                // Held until the test closes this process's input.
            }
        }
    }

}
