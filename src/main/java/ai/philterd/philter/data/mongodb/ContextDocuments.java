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

import ai.philterd.philter.data.entities.ContextEntity;
import org.bson.Document;

/** Maps {@link ContextEntity} to and from the documents MongoDB stores. */
public final class ContextDocuments {

    private ContextDocuments() {
    }

    public static ContextEntity fromDocument(final Document document) {
        final ContextEntity contextEntity = new ContextEntity();
        contextEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        contextEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        contextEntity.setContextName(document.getString("context_name"));
        contextEntity.setMaxSize(document.getInteger("max_size", ContextEntity.MAX_CONTEXT_SIZE));
        contextEntity.setDisambiguation(document.getBoolean("disambiguation", false));
        contextEntity.setDisambiguationScope(document.getString("disambiguation_scope") != null
                ? document.getString("disambiguation_scope") : ContextEntity.DISAMBIGUATION_SCOPE_DOCUMENT);
        contextEntity.setLedger(document.getBoolean("ledger", false));
        contextEntity.setSlot(document.getInteger("slot"));
        contextEntity.setTtlInHours(document.getInteger("ttl_in_hours", ContextEntity.DEFAULT_TTL_IN_HOURS));
        contextEntity.setTimestamp(document.getDate("timestamp"));
        return contextEntity;
    }

    public static Document toDocument(final ContextEntity entity) {
        final Document document = new Document();
        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("context_name", entity.getContextName());
        document.put("max_size", entity.getMaxSize());
        document.put("disambiguation", entity.isDisambiguation());
        document.put("disambiguation_scope", entity.getDisambiguationScope());
        document.put("ledger", entity.isLedger());
        document.put("slot", entity.getSlot());
        document.put("ttl_in_hours", entity.getTtlInHours());
        document.put("timestamp", entity.getTimestamp());
        return document;
    }

}
