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

import ai.philterd.philter.data.entities.PolicyVersionEntity;
import org.bson.Document;

/** Maps {@link PolicyVersionEntity} to and from the documents MongoDB stores. */
public final class PolicyVersionDocuments {

    private PolicyVersionDocuments() {
    }

    public static PolicyVersionEntity fromDocument(final Document document) {
        final PolicyVersionEntity entity = new PolicyVersionEntity();
        entity.setId(MongoIds.hex(document.getObjectId("_id")));
        entity.setName(document.getString("name"));
        entity.setRevision(document.getInteger("revision", 0));
        entity.setContentHash(document.getString("content_hash"));
        entity.setPolicy(document.getString("policy"));
        entity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        entity.setCapturedTimestamp(document.getDate("captured_timestamp"));
        entity.setAuthorId(MongoIds.hex(document.getObjectId("author_id")));
        return entity;
    }

    public static Document toDocument(final PolicyVersionEntity entity) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("name", entity.getName());
        document.put("revision", entity.getRevision());
        document.put("content_hash", entity.getContentHash());
        document.put("policy", entity.getPolicy());
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("captured_timestamp", entity.getCapturedTimestamp());
        if (entity.getAuthorId() != null) {
            document.put("author_id", MongoIds.objectId(entity.getAuthorId()));
        }
        return document;
    }

}
