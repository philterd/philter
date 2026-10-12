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

import ai.philterd.philter.data.entities.ContextEntryEntity;
import org.bson.Document;

/** Maps {@link ContextEntryEntity} to and from the documents MongoDB stores. */
public final class ContextEntryDocuments {

    private ContextEntryDocuments() {
    }

    public static ContextEntryEntity fromDocument(final Document document) {
        final ContextEntryEntity contextEntryEntity = new ContextEntryEntity();
        contextEntryEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        contextEntryEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        contextEntryEntity.setContextName(document.getString("context_name"));
        contextEntryEntity.setTokenHash(document.getString("token_hash"));
        contextEntryEntity.setReplacement(document.getString("replacement"));
        contextEntryEntity.setReplacementUuid(document.getBoolean("replacement_uuid", false));
        contextEntryEntity.setReads(document.getLong("reads"));
        contextEntryEntity.setTimestamp(document.getDate("timestamp"));
        contextEntryEntity.setFilterType(document.getString("filter_type"));
        return contextEntryEntity;
    }

    public static Document toDocument(final ContextEntryEntity entity) {
        final Document document = new Document();
        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("context_name", entity.getContextName());
        document.put("token_hash", entity.getTokenHash());
        document.put("replacement", entity.getReplacement());
        document.put("replacement_uuid", entity.isReplacementUuid());
        document.put("reads", entity.getReads());
        document.put("timestamp", entity.getTimestamp());
        document.put("filter_type", entity.getFilterType());
        return document;
    }

}
