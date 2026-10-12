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

import ai.philterd.philter.data.entities.SigningKeyEntity;
import ai.philterd.philter.services.encryption.EncryptedBytes;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.Binary;

/** Maps {@link SigningKeyEntity} to and from the documents MongoDB stores. */
public final class SigningKeyDocuments {

    /** The encryption owner the signing keys are encrypted for: they belong to Philter, not a user. */
    private static final String SYSTEM_OWNER = "system";

    private SigningKeyDocuments() {
    }

    public static SigningKeyEntity fromDocument(final Document doc, final EncryptionService encryptionService) {
        final SigningKeyEntity e = new SigningKeyEntity();
        e.setId(MongoIds.hex(doc.getObjectId("_id")));
        e.setKeyId(doc.getString("key_id"));

        // The public key stays in the clear: GET /api/signing-key serves it unauthenticated so a
        // third party can verify signatures, and it is not a secret. Only the private half is
        // encrypted, so a database dump alone no longer yields the ability to forge signatures.
        final byte[] storedPrivate = doc.get("private_key", Binary.class).getData();
        final String wrappedKey = doc.getString("private_key_encrypted_key");
        if (wrappedKey == null || wrappedKey.isBlank()) {
            throw new IllegalStateException("Signing private key is missing its encryption key.");
        }
        e.setPrivateKeyEncoded(encryptionService.decryptBytes(storedPrivate, wrappedKey));
        e.setPublicKeyEncoded(doc.get("public_key", Binary.class).getData());
        e.setCreatedAt(doc.getDate("created_at"));
        return e;
    }

    public static Document toDocument(final SigningKeyEntity entity, final EncryptionService encryptionService) {
        final Document doc = new Document();
        if (entity.getId() != null) {
            doc.put("_id", MongoIds.objectId(entity.getId()));
        }
        doc.put("key_id", entity.getKeyId());

        final EncryptedBytes encrypted = encryptionService.encryptBytes(entity.getPrivateKeyEncoded(), SYSTEM_OWNER);
        doc.put("private_key", new Binary(encrypted.ciphertext()));
        doc.put("private_key_encrypted_key", encrypted.encryptionKey());

        doc.put("public_key", entity.getPublicKeyEncoded());
        doc.put("created_at", entity.getCreatedAt());
        return doc;
    }

    /** Reads the record without touching the private key, for callers that only need the public half. */
    public static SigningKeyEntity publicPartFromDocument(final Document doc) {
        final SigningKeyEntity e = new SigningKeyEntity();
        e.setId(MongoIds.hex(doc.getObjectId("_id")));
        e.setKeyId(doc.getString("key_id"));
        e.setPublicKeyEncoded(doc.get("public_key", Binary.class).getData());
        e.setCreatedAt(doc.getDate("created_at"));
        return e;
    }

}
