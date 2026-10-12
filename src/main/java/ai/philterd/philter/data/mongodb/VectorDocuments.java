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

import ai.philterd.philter.data.entities.VectorEntity;
import org.bson.Document;

/** Maps {@link VectorEntity} to and from the documents MongoDB stores. */
public final class VectorDocuments {

    private VectorDocuments() {
    }

    public static VectorEntity fromDocument(final Document document) {
        final VectorEntity vectorEntity = new VectorEntity();
        vectorEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        vectorEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        vectorEntity.setContext(document.getString("context"));
        vectorEntity.setHash(document.getDouble("hash"));
        vectorEntity.setVectorSize(document.getInteger("vector_size"));
        vectorEntity.setFilterType(document.getString("filter_type"));
        return vectorEntity;
    }

    public static Document toDocument(final VectorEntity entity) {
        final Document document = new Document();
        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("context", entity.getContext());
        document.put("hash", entity.getHash());
        document.put("vector_size", entity.getVectorSize());
        document.put("filter_type", entity.getFilterType());
        return document;
    }

}
