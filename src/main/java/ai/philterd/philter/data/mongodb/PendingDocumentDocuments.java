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

import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.Binary;

/** Maps {@link PendingDocumentEntity} to and from the documents MongoDB stores. */
public final class PendingDocumentDocuments {

    private PendingDocumentDocuments() {
    }

    public static PendingDocumentEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setId(MongoIds.hex(document.getObjectId("_id")));
        entity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        entity.setDocumentId(document.getString("document_id"));
        entity.setFileName(document.getString("file_name"));
        entity.setInputMimeType(document.getString("input_mime_type"));
        entity.setOutputMimeType(document.getString("output_mime_type"));
        entity.setPolicyName(document.getString("policy_name"));
        entity.setPolicyVersion(document.getInteger("policy_version", -1));
        entity.setPolicyContentHash(document.getString("policy_content_hash"));
        entity.setContextName(document.getString("context_name"));
        entity.setClientIpAddress(document.getString("client_ip_address"));
        entity.setEffectiveHash(document.getString("effective_hash"));
        entity.setEffectiveJson(SecretFields.open(encryptionService, document.getString("effective_json"), document.getString("effective_key")));
        entity.setStatus(document.getString("status"));
        entity.setErrorMessage(document.getString("error_message"));

        // The submitted document is the most sensitive thing Philter holds, so it is encrypted at
        // rest like every other sensitive collection. Bytes are encrypted as bytes: routing them
        // through the String path would base64 twice and push a 10 MB PDF past MongoDB's 16 MB limit.
        if (document.get("input") instanceof Binary input) {
            entity.setInput(SecretFields.openBytes(encryptionService, input.getData(), document.getString("input_encrypted_key")));
        }
        if (document.get("output") instanceof Binary output) {
            entity.setOutput(SecretFields.openBytes(encryptionService, output.getData(), document.getString("output_encrypted_key")));
        }

        entity.setSubmittedAt(document.getDate("submitted_at"));
        entity.setStartedAt(document.getDate("started_at"));
        entity.setCompletedAt(document.getDate("completed_at"));
        entity.setClaimedBy(document.getString("claimed_by"));
        entity.setClaimedAt(document.getDate("claimed_at"));
        entity.setClaimToken(document.getString("claim_token"));
        entity.setClaimExpiresAt(document.getDate("claim_expires_at"));
        entity.setReclaimCount(document.getInteger("reclaim_count", 0));
        return entity;
    }

    public static Document toDocument(final PendingDocumentEntity entity, final EncryptionService encryptionService) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("document_id", entity.getDocumentId());
        document.put("file_name", entity.getFileName());
        document.put("input_mime_type", entity.getInputMimeType());
        document.put("output_mime_type", entity.getOutputMimeType());
        document.put("policy_name", entity.getPolicyName());
        document.put("policy_version", entity.getPolicyVersion());
        document.put("policy_content_hash", entity.getPolicyContentHash());
        document.put("context_name", entity.getContextName());
        document.put("client_ip_address", entity.getClientIpAddress());
        document.put("input_size", entity.getInput() == null ? 0L : (long) entity.getInput().length);
        document.put("effective_hash", entity.getEffectiveHash());
        final SecretFields.Sealed effective = SecretFields.seal(encryptionService, entity.getEffectiveJson(), entity.getUserId());
        if (effective != null) {
            document.put("effective_json", effective.ciphertext());
            document.put("effective_key", effective.key());
        }
        document.put("status", entity.getStatus());
        document.put("error_message", entity.getErrorMessage());
        final SecretFields.SealedBytes input = SecretFields.sealBytes(encryptionService, entity.getInput(), entity.getUserId());
        if (input != null) {
            document.put("input", new Binary(input.ciphertext()));
            document.put("input_encrypted_key", input.key());
        }
        final SecretFields.SealedBytes output = SecretFields.sealBytes(encryptionService, entity.getOutput(), entity.getUserId());
        if (output != null) {
            document.put("output", new Binary(output.ciphertext()));
            document.put("output_encrypted_key", output.key());
        }
        document.put("submitted_at", entity.getSubmittedAt());
        document.put("started_at", entity.getStartedAt());
        document.put("completed_at", entity.getCompletedAt());
        document.put("claimed_by", entity.getClaimedBy());
        document.put("claimed_at", entity.getClaimedAt());
        document.put("claim_token", entity.getClaimToken());
        document.put("claim_expires_at", entity.getClaimExpiresAt());
        document.put("reclaim_count", entity.getReclaimCount());
        return document;
    }

}
