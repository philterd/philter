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
package ai.philterd.philter.data.entities;

import ai.philterd.philter.services.encryption.EncryptedBytes;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import java.util.Date;

public class SigningKeyEntity extends AbstractEncryptedEntity {

    /** The signing key belongs to the instance, not a user; the key provider ignores this value. */
    private static final String SYSTEM_OWNER = "system";

    private ObjectId id;
    private String keyId;
    private byte[] privateKeyEncoded;
    private byte[] publicKeyEncoded;
    private Date createdAt;
    /** Reads the record without touching the private key, for callers that only need the public half. */
    public static SigningKeyEntity publicPartFromDocument(final Document doc) {
        final SigningKeyEntity e = new SigningKeyEntity();
        e.setId(doc.getObjectId("_id"));
        e.setKeyId(doc.getString("key_id"));
        e.setPublicKeyEncoded(doc.get("public_key", Binary.class).getData());
        e.setCreatedAt(doc.getDate("created_at"));
        return e;
    }

    public static SigningKeyEntity fromDocument(final Document doc, final EncryptionService encryptionService) {
        final SigningKeyEntity e = new SigningKeyEntity();
        e.setId(doc.getObjectId("_id"));
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

    @Override
    public Document toDocument(final EncryptionService encryptionService) {
        final Document doc = new Document();
        if (id != null) {
            doc.put("_id", id);
        }
        doc.put("key_id", keyId);

        final EncryptedBytes encrypted = encryptionService.encryptBytes(privateKeyEncoded, SYSTEM_OWNER);
        doc.put("private_key", new Binary(encrypted.ciphertext()));
        doc.put("private_key_encrypted_key", encrypted.encryptionKey());

        doc.put("public_key", publicKeyEncoded);
        doc.put("created_at", createdAt);
        return doc;
    }

    @Override
    public ObjectId getId() {
        return id;
    }

    public void setId(final ObjectId id) {
        this.id = id;
    }

    public byte[] getPrivateKeyEncoded() {
        return privateKeyEncoded;
    }

    public void setPrivateKeyEncoded(final byte[] privateKeyEncoded) {
        this.privateKeyEncoded = privateKeyEncoded;
    }

    public byte[] getPublicKeyEncoded() {
        return publicKeyEncoded;
    }

    public void setPublicKeyEncoded(final byte[] publicKeyEncoded) {
        this.publicKeyEncoded = publicKeyEncoded;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(final String keyId) {
        this.keyId = keyId;
    }

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Date createdAt) {
        this.createdAt = createdAt;
    }

}
