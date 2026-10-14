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
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;

/** Maps {@link PolicyEntity} to and from the documents MongoDB stores. */
public final class PolicyDocuments {

    private PolicyDocuments() {
    }

    // The policy, its description, and its notes are encrypted: a policy can hold the terms it redacts and keys.
    public static PolicyEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final PolicyEntity policyEntity = new PolicyEntity();
        policyEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        policyEntity.setPolicy(SecretFields.open(encryptionService, document.getString("policy"), document.getString("policy_key")));
        policyEntity.setName(document.getString("name"));
        policyEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        policyEntity.setCreatedTimestamp(document.getDate("created_timestamp"));
        policyEntity.setRevision(document.getInteger("revision", 0));
        policyEntity.setLastUpdatedTimestamp(document.getDate("last_updated_timestamp"));
        policyEntity.setNotes(SecretFields.open(encryptionService, document.getString("notes"), document.getString("notes_key")));
        policyEntity.setDescription(SecretFields.open(encryptionService, document.getString("description"),
                document.getString("description_key")));
        policyEntity.setDetailsRevision(document.getInteger("details_revision", 0));
        policyEntity.setManaged(document.getBoolean("managed", false));
        return policyEntity;
    }

    public static Document toDocument(final PolicyEntity entity, final EncryptionService encryptionService) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        putSealed(document, "policy", "policy_key", SecretFields.seal(encryptionService, entity.getPolicy(), entity.getUserId()));
        document.put("name", entity.getName());
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("created_timestamp", entity.getCreatedTimestamp());
        document.put("revision", entity.getRevision());
        document.put("last_updated_timestamp", entity.getLastUpdatedTimestamp());
        putSealed(document, "notes", "notes_key", SecretFields.seal(encryptionService, entity.getNotes(), entity.getUserId()));
        putSealed(document, "description", "description_key",
                SecretFields.seal(encryptionService, entity.getDescription(), entity.getUserId()));
        document.put("details_revision", entity.getDetailsRevision());
        document.put("managed", entity.isManaged());
        return document;
    }

    /** Puts a sealed value and its key, or nulls for a value that is not set. */
    static void putSealed(final Document document, final String field, final String keyField, final SecretFields.Sealed sealed) {
        document.put(field, sealed == null ? null : sealed.ciphertext());
        document.put(keyField, sealed == null ? null : sealed.key());
    }

}
