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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.data.schema.Migration;

/**
 * Version 2: a context's values, such as its random date shift, are kept in its document's {@code context_values}.
 * A document without the field has no values, so nothing is changed; the version keeps MongoDB storage at the same
 * schema version as SQLite storage, which a storage migration requires.
 */
final class MongoContextValuesMigration implements Migration {

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "Contexts hold context_values";
    }

    @Override
    public void apply() {
    }

}
