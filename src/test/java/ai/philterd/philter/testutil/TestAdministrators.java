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
package ai.philterd.philter.testutil;

import com.mongodb.client.MongoClient;
import org.bson.Document;
import org.bson.types.ObjectId;

/** Seeds administrators for changes that recheck the acting user, such as rotating the signing key. */
public final class TestAdministrators {

    private TestAdministrators() {
    }

    public static ObjectId create(final MongoClient mongoClient) {
        return create(mongoClient, new ObjectId());
    }

    public static ObjectId create(final MongoClient mongoClient, final ObjectId id) {
        mongoClient.getDatabase("philter").getCollection("users").replaceOne(new Document("_id", id),
                new Document("_id", id).append("username", "admin-" + id).append("role", "admin"),
                new com.mongodb.client.model.ReplaceOptions().upsert(true));
        return id;
    }

}
