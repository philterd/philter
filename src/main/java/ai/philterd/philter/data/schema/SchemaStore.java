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
package ai.philterd.philter.data.schema;

import java.time.Duration;
import java.time.Instant;

/** Where a storage implementation keeps the version of Philter's data layout, and the lock that guards changing it. */
public interface SchemaStore {

    /** The recorded version, or {@code null} when none has been recorded. */
    Integer currentVersion();

    /** Whether the database holds any of Philter's data, to tell a fresh database from one written before versions. */
    boolean hasData();

    /** Records the version of a database that had none. */
    void initialize(int version, Instant at);

    /** Records that a migration completed, moving the version to its version, in one write. */
    void recordMigration(int version, String description, Instant ranAt);

    /**
     * Takes the lock for {@code owner}, or extends it when {@code owner} already holds it, until {@code lease}
     * from now. Returns {@code false} without waiting while another owner holds an unexpired lock.
     */
    boolean acquireLock(String owner, Duration lease);

    /** Releases the lock if {@code owner} holds it. */
    void releaseLock(String owner);

}
