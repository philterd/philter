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

import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;

/** Maps {@link WebhookDeliveryEntity} to and from the documents MongoDB stores. */
public final class WebhookDeliveryDocuments {

    private WebhookDeliveryDocuments() {
    }

    public static WebhookDeliveryEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setId(MongoIds.hex(document.getObjectId("_id")));
        entity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        entity.setDocumentId(document.getString("document_id"));
        entity.setEventType(document.getString("event_type"));
        entity.setStatus(document.getString("status"));
        entity.setUrl(document.getString("url"));
        final String storedSecret = document.getString("secret");
        if (storedSecret != null && !storedSecret.isEmpty()) {
            final String encryptedKey = document.getString("secret_encrypted_key");
            if (encryptedKey == null || encryptedKey.isBlank()) {
                throw new IllegalStateException("Webhook signing secret has not been encrypted.");
            }
            entity.setSecret(encryptionService.decrypt(storedSecret, encryptedKey));
        } else {
            entity.setSecret(storedSecret);
        }
        entity.setPayload(document.getString("payload"));
        entity.setAttempts(document.getInteger("attempts", 0));
        entity.setClaimToken(document.getString("claim_token"));
        entity.setClaimExpiresAt(document.getDate("claim_expires_at"));
        entity.setLastError(document.getString("last_error"));
        entity.setNextAttemptAt(document.getDate("next_attempt_at"));
        entity.setCreatedAt(document.getDate("created_at"));
        entity.setUpdatedAt(document.getDate("updated_at"));
        entity.setDeliveredAt(document.getDate("delivered_at"));
        entity.setCompletedAt(document.getDate("completed_at"));
        return entity;
    }

    public static Document toDocument(final WebhookDeliveryEntity entity, final EncryptionService encryptionService) {
        final Document document = new Document();
        if (entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("document_id", entity.getDocumentId());
        document.put("event_type", entity.getEventType());
        document.put("status", entity.getStatus());
        document.put("url", entity.getUrl());
        if (entity.getSecret() != null && !entity.getSecret().isEmpty()) {
            final EncryptResult encrypted = encryptionService.encrypt(entity.getSecret(), entity.getUserId());
            document.put("secret", encrypted.getEncryptedText());
            document.put("secret_encrypted_key", encrypted.getEncryptionKey());
        } else {
            document.put("secret", entity.getSecret());
            document.put("secret_encrypted_key", null);
        }
        document.put("payload", entity.getPayload());
        document.put("attempts", entity.getAttempts());
        document.put("claim_token", entity.getClaimToken());
        document.put("claim_expires_at", entity.getClaimExpiresAt());
        document.put("last_error", entity.getLastError());
        document.put("next_attempt_at", entity.getNextAttemptAt());
        document.put("created_at", entity.getCreatedAt());
        document.put("updated_at", entity.getUpdatedAt());
        document.put("delivered_at", entity.getDeliveredAt());
        document.put("completed_at", entity.getCompletedAt());
        return document;
    }

}
