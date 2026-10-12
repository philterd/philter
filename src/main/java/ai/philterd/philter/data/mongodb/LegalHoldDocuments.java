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

import ai.philterd.philter.data.entities.LegalHoldEntity;
import org.bson.Document;

/** Maps {@link LegalHoldEntity} to and from the documents MongoDB stores. */
public final class LegalHoldDocuments {

    private LegalHoldDocuments() {
    }

    public static LegalHoldEntity fromDocument(final Document document) {
        final LegalHoldEntity entity = new LegalHoldEntity();
        entity.setId(MongoIds.hex(document.getObjectId("_id")));
        entity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        entity.setReference(document.getString("reference"));
        entity.setScopeType(document.getString("scope_type"));
        entity.setScopeValue(document.getString("scope_value"));
        entity.setReason(document.getString("reason"));
        entity.setSetAt(document.getDate("set_at"));
        entity.setSetByUserId(MongoIds.hex(document.getObjectId("set_by_user_id")));
        return entity;
    }

    public static Document toDocument(final LegalHoldEntity entity) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("reference", entity.getReference());
        document.put("scope_type", entity.getScopeType());
        document.put("scope_value", entity.getScopeValue());
        document.put("reason", entity.getReason());
        document.put("set_at", entity.getSetAt());
        document.put("set_by_user_id", MongoIds.objectId(entity.getSetByUserId()));
        return document;
    }

}
