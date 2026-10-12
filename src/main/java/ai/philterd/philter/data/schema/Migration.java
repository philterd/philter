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

/**
 * One change to how Philter's data is stored, run once when Philter starts against data at the version before
 * it. Version 1 is the layout Philter 4.0 shipped with, so the first migration is version 2, and each migration's
 * version is one more than the one before.
 *
 * <p>A migration runs before Philter serves any request. It should be safe to run again: if Philter stops after
 * a migration's changes but before they are recorded, the migration runs again at the next start.
 */
public interface Migration {

    /** The version the data is at once this migration has run. */
    int version();

    /** What the migration changes, for the log and the recorded history. */
    String description();

    /** Makes the change. Any exception stops Philter from starting, with the version left where it was. */
    void apply() throws Exception;

}
