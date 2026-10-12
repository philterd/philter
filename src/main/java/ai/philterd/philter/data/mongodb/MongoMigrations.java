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
import com.mongodb.client.MongoClient;

import java.util.List;

/**
 * The migrations of the MongoDB storage, in order. Add each new one at the end, numbered one more than the last,
 * as a class in this package that changes the stored documents through {@code mongoClient}.
 */
final class MongoMigrations {

    private MongoMigrations() {
    }

    static List<Migration> all(final MongoClient mongoClient) {
        // Version 1 is the layout Philter 4.0 shipped with; there are no migrations yet.
        return List.of();
    }

}
