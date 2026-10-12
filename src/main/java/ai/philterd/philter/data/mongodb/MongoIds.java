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

import org.bson.types.ObjectId;
import java.util.Collection;
import java.util.List;

/**
 * Converts record ids between the 24-character hex strings Philter uses outside its MongoDB storage and the
 * {@link ObjectId} values MongoDB stores, so the stored documents keep their {@link ObjectId} fields.
 */
public final class MongoIds {

    private MongoIds() {
    }

    /** The stored form of an id, or {@code null} for {@code null}. Throws for a value that is not an id. */
    public static ObjectId objectId(final String id) {
        return id == null ? null : new ObjectId(id);
    }

    /** The stored form of each id, for an {@code $in} query. */
    public static List<ObjectId> objectIds(final Collection<String> ids) {
        return ids.stream().map(MongoIds::objectId).toList();
    }

    /** The id of a stored value, or {@code null} for {@code null}. */
    public static String hex(final ObjectId id) {
        return id == null ? null : id.toHexString();
    }

    /** A new id. */
    public static String newId() {
        return new ObjectId().toHexString();
    }

}
