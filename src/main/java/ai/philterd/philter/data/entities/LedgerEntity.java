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

import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

public class LedgerEntity extends AbstractEncryptedEntity {

    private ObjectId id;
    private String documentId;
    private String token;
    private String tokenEncryptedKey;
    private String replacement;
    private String replacementEncryptedKey;
    private long startPosition;
    private String documentHash;
    private Date timestamp;
    private String previousHash;
    private String hash;
    private String filename;
    private String type;
    private ObjectId userId;

    public LedgerEntity() {

    }

    public LedgerEntity(ObjectId userId, String documentId, String token, String replacement,
                        long startPosition, String documentHash, String previousHash, String filename, String type, final EncryptionService encryptionService) throws NoSuchAlgorithmException {

        final EncryptResult tokenEncryptResult = encryptionService.encrypt(token, userId.toHexString());
        this.token = tokenEncryptResult.getEncryptedText();
        this.tokenEncryptedKey = tokenEncryptResult.getEncryptionKey();

        final EncryptResult replacementEncryptResult = encryptionService.encrypt(token, userId.toHexString());
        this.replacement = replacementEncryptResult.getEncryptedText();
        this.replacementEncryptedKey = replacementEncryptResult.getEncryptionKey();

        this.userId = userId;
        this.documentId = documentId;
        this.startPosition = startPosition;
        this.documentHash = documentHash;
        this.timestamp = new Date();
        this.previousHash = previousHash;
        this.filename = filename;
        this.type = type;
        this.hash = calculateHash();

    }

    public static LedgerEntity fromDocument(final Document document, final EncryptionService encryptionService) {

        final LedgerEntity ledgerEntity = new LedgerEntity();
        ledgerEntity.id = document.getObjectId("_id");
        ledgerEntity.userId = document.getObjectId("user_id");
        ledgerEntity.documentId = document.getString("document_id");
        ledgerEntity.startPosition = document.getLong("start_position");
        ledgerEntity.documentHash = document.getString("document_hash");
        ledgerEntity.previousHash = document.getString("previous_hash");
        ledgerEntity.hash = document.getString("hash");
        ledgerEntity.timestamp = document.getDate("timestamp");
        ledgerEntity.filename = document.getString("filename");
        ledgerEntity.type = document.getString("type");

        final String tokenEncryptedKey = document.getString("token_encrypted_key");
        ledgerEntity.token = encryptionService.decrypt(document.getString("token"), tokenEncryptedKey);
        ledgerEntity.tokenEncryptedKey = tokenEncryptedKey;

        final String replacementEncryptedKey = document.getString("replacement_encrypted_key");
        ledgerEntity.replacement = encryptionService.decrypt(document.getString("replacement"), replacementEncryptedKey);
        ledgerEntity.replacementEncryptedKey = replacementEncryptedKey;

        return ledgerEntity;

    }

    @Override
    public Document toDocument(final EncryptionService encryptionService) {

        final Document document = new Document();

        if(id != null) {
            document.put("_id", id);
        }

        final EncryptResult tokenEncryptResult = encryptionService.encrypt(token, userId.toHexString());
        document.put("token", tokenEncryptResult.getEncryptedText());
        document.put("token_encrypted_key", tokenEncryptResult.getEncryptionKey());

        final EncryptResult replacementEncryptResult = encryptionService.encrypt(replacement, userId.toHexString());
        document.put("replacement", replacementEncryptResult.getEncryptedText());
        document.put("replacement_encrypted_key", replacementEncryptResult.getEncryptionKey());

        document.put("user_id", userId);
        document.put("document_id", documentId);
        document.put("start_position", startPosition);
        document.put("document_hash", documentHash);
        document.put("previous_hash", previousHash);
        document.put("hash", hash);
        document.put("timestamp", timestamp);
        document.put("filename", filename);
        document.put("type", type);

        return document;

    }

    public String calculateHash() throws NoSuchAlgorithmException {

        final String dataToHash = userId + documentId + token + replacement + startPosition + documentHash + timestamp + previousHash;

        final MessageDigest digest = MessageDigest.getInstance("SHA-256");

        final byte[] bytes = digest.digest(dataToHash.getBytes());

        final StringBuilder buffer = new StringBuilder();

        for (final byte b : bytes) {
            buffer.append(String.format("%02x", b));
        }

        return buffer.toString();

    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getDocumentId() {
        return documentId;
    }

    public void setDocumentId(String documentId) {
        this.documentId = documentId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    public long getStartPosition() {
        return startPosition;
    }

    public void setStartPosition(long startPosition) {
        this.startPosition = startPosition;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    public void setDocumentHash(String documentHash) {
        this.documentHash = documentHash;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public ObjectId getUserId() {
        return userId;
    }

}
