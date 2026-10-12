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

import ai.philterd.philter.data.entities.PolicyEntity;
import org.bson.Document;

/** Maps {@link PolicyEntity} to and from the documents MongoDB stores. */
public final class PolicyDocuments {

    private PolicyDocuments() {
    }

    public static PolicyEntity fromDocument(final Document document) {
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        policyEntity.setPolicy(document.getString("policy"));
        policyEntity.setName(document.getString("name"));
        policyEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        policyEntity.setCreatedTimestamp(document.getDate("created_timestamp"));
        policyEntity.setRevision(document.getInteger("revision", 0));
        policyEntity.setLastUpdatedTimestamp(document.getDate("last_updated_timestamp"));
        policyEntity.setShared(document.getBoolean("shared", false));
        policyEntity.setNotes(document.getString("notes"));
        policyEntity.setDescription(document.getString("description"));
        policyEntity.setManaged(document.getBoolean("managed", false));
        return policyEntity;
    }

    public static Document toDocument(final PolicyEntity entity) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("policy", entity.getPolicy());
        document.put("name", entity.getName());
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("created_timestamp", entity.getCreatedTimestamp());
        document.put("revision", entity.getRevision());
        document.put("last_updated_timestamp", entity.getLastUpdatedTimestamp());
        document.put("shared", entity.isShared());
        document.put("notes", entity.getNotes());
        document.put("description", entity.getDescription());
        document.put("managed", entity.isManaged());
        return document;
    }

}
