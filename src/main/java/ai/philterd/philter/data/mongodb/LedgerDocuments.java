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

import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.Date;

/** Maps {@link LedgerEntity} to and from the documents MongoDB stores. */
public final class LedgerDocuments {

    private LedgerDocuments() {
    }

    public static LedgerEntity fromDocument(final Document document, final EncryptionService encryptionService) {

        final LedgerEntity ledgerEntity = new LedgerEntity();
        ledgerEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        ledgerEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));
        ledgerEntity.setDocumentId(document.getString("document_id"));
        ledgerEntity.setStartPosition(document.getLong("start_position"));
        ledgerEntity.setDocumentHash(document.getString("document_hash"));
        ledgerEntity.setPreviousHash(document.getString("previous_hash"));
        ledgerEntity.setHash(document.getString("hash"));
        ledgerEntity.setTimestamp(document.getDate("timestamp"));
        ledgerEntity.setFilename(document.getString("filename"));
        ledgerEntity.setType(document.getString("type"));
        ledgerEntity.setPolicyName(document.getString("policy_name"));
        ledgerEntity.setPolicyVersion(document.getInteger("policy_version", 0));
        ledgerEntity.setPolicyContentHash(document.getString("policy_content_hash"));
        ledgerEntity.setEffectiveHash(document.getString("effective_hash"));
        ledgerEntity.setSignature(document.getString("signature"));
        ledgerEntity.setSigningKeyId(document.getString("signing_key_id"));

        final String tokenEncryptedKey = document.getString("token_encrypted_key");
        ledgerEntity.setToken(encryptionService.decrypt(document.getString("token"), tokenEncryptedKey));
        ledgerEntity.setTokenEncryptedKey(tokenEncryptedKey);

        final String replacementEncryptedKey = document.getString("replacement_encrypted_key");
        ledgerEntity.setReplacement(encryptionService.decrypt(document.getString("replacement"), replacementEncryptedKey));
        ledgerEntity.setReplacementEncryptedKey(replacementEncryptedKey);

        return ledgerEntity;

    }

    public static Document toDocument(final LedgerEntity entity, final EncryptionService encryptionService) {

        final Document document = new Document();

        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }

        final EncryptResult tokenEncryptResult = encryptionService.encrypt(entity.getToken(), entity.getUserId());
        document.put("token", tokenEncryptResult.getEncryptedText());
        document.put("token_encrypted_key", tokenEncryptResult.getEncryptionKey());

        final EncryptResult replacementEncryptResult = encryptionService.encrypt(entity.getReplacement(), entity.getUserId());
        document.put("replacement", replacementEncryptResult.getEncryptedText());
        document.put("replacement_encrypted_key", replacementEncryptResult.getEncryptionKey());

        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        document.put("document_id", entity.getDocumentId());
        document.put("start_position", entity.getStartPosition());
        document.put("document_hash", entity.getDocumentHash());
        document.put("previous_hash", entity.getPreviousHash());
        document.put("hash", entity.getHash());
        document.put("timestamp", entity.getTimestamp());
        document.put("filename", entity.getFilename());
        document.put("type", entity.getType());
        document.put("policy_name", entity.getPolicyName());
        document.put("policy_version", entity.getPolicyVersion());
        document.put("policy_content_hash", entity.getPolicyContentHash());
        document.put("effective_hash", entity.getEffectiveHash());
        document.put("signature", entity.getSignature());
        document.put("signing_key_id", entity.getSigningKeyId());

        return document;

    }

    /**
     * Reads an entry, or, when it cannot be read (its token or replacement no longer decrypts, or a field
     * has the wrong type), an entry marked unreadable that carries only the fields stored in the clear, so
     * a listing can still show it. The encrypted token and replacement are left null.
     */
    public static LedgerEntity fromDocumentOrUnreadable(final Document document, final EncryptionService encryptionService) {
        try {
            return fromDocument(document, encryptionService);
        } catch (final RuntimeException unreadableEntry) {
            final LedgerEntity entity = new LedgerEntity();
            entity.markUnreadable(unreadableEntry);
            entity.setId(document.get("_id") instanceof ObjectId id ? MongoIds.hex(id) : null);
            entity.setUserId(document.get("user_id") instanceof ObjectId user ? MongoIds.hex(user) : null);
            entity.setDocumentId(text(document, "document_id"));
            entity.setFilename(text(document, "filename"));
            entity.setType(text(document, "type"));
            entity.setDocumentHash(text(document, "document_hash"));
            entity.setPreviousHash(text(document, "previous_hash"));
            entity.setHash(text(document, "hash"));
            entity.setPolicyName(text(document, "policy_name"));
            entity.setPolicyContentHash(text(document, "policy_content_hash"));
            entity.setEffectiveHash(text(document, "effective_hash"));
            entity.setSignature(text(document, "signature"));
            entity.setSigningKeyId(text(document, "signing_key_id"));
            entity.setTimestamp(document.get("timestamp") instanceof Date date ? date : null);
            entity.setStartPosition(document.get("start_position") instanceof Number number ? number.longValue() : 0);
            entity.setPolicyVersion(document.get("policy_version") instanceof Number number ? number.intValue() : 0);
            return entity;
        }
    }

    private static String text(final Document document, final String field) {
        return document.get(field) instanceof String value ? value : null;
    }

}
