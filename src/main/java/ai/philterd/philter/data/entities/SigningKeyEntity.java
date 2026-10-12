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


import java.util.Date;

public class SigningKeyEntity extends AbstractEncryptedEntity {

    private String id;
    private String keyId;
    private byte[] privateKeyEncoded;
    private byte[] publicKeyEncoded;
    private Date createdAt;

    @Override
    public String getId() {
        return id;
    }

    public void setId(final String id) {
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
