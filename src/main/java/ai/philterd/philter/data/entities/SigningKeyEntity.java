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

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import java.util.Date;

public class SigningKeyEntity extends AbstractEntity {

    private ObjectId id;
    private byte[] privateKeyEncoded;
    private byte[] publicKeyEncoded;
    private Date createdAt;

    public static SigningKeyEntity fromDocument(final Document doc) {
        final SigningKeyEntity e = new SigningKeyEntity();
        e.setId(doc.getObjectId("_id"));
        e.setPrivateKeyEncoded(doc.get("private_key", Binary.class).getData());
        e.setPublicKeyEncoded(doc.get("public_key", Binary.class).getData());
        e.setCreatedAt(doc.getDate("created_at"));
        return e;
    }

    @Override
    public Document toDocument() {
        final Document doc = new Document();
        if (id != null) {
            doc.put("_id", id);
        }
        doc.put("private_key", privateKeyEncoded);
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

    public Date getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(final Date createdAt) {
        this.createdAt = createdAt;
    }

}
