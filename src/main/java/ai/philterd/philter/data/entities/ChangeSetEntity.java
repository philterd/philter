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

import java.util.UUID;

public class ChangeSetEntity extends AbstractEncryptedEntity {

    private ObjectId id;
    private ObjectId userId;
    private String documentId;
    private String token;
    private String tokenEncryptedKey;
    private int characterStart;
    private int characterEnd;
    private String replacement;
    private String replacementEncryptedKey;
    private String type;
    private double confidence;
    private int paragraph;      // Only applies to DOCX files
    private int page;
    private int lineNumber;     // Only applies to PDF files
    private int version;        // Used to version changesets
    private double lowerLeftX;
    private double lowerLeftY;
    private double upperRightX;
    private double upperRightY;
    private String lineHash;
    private String uuid;

    public ChangeSetEntity() {
        this.uuid = UUID.randomUUID().toString();
    }

    public static ChangeSetEntity fromDocument(final Document document, final EncryptionService encryptionService) {

        final ChangeSetEntity changeSetEntity = new ChangeSetEntity();
        changeSetEntity.setId(document.getObjectId("_id"));
        changeSetEntity.setUserId(document.getObjectId("user_id"));
        changeSetEntity.setDocumentId(document.getString("document_id"));
        changeSetEntity.setCharacterStart(document.getInteger("character_start"));
        changeSetEntity.setCharacterEnd(document.getInteger("character_end"));
        changeSetEntity.setType(document.getString("type"));
        changeSetEntity.setConfidence(document.getDouble("confidence"));
        changeSetEntity.setParagraph(document.getInteger("paragraph", 0));
        changeSetEntity.setPage(document.getInteger("page", 0));
        changeSetEntity.setLineNumber(document.getInteger("line_number", 0));
        changeSetEntity.setVersion(document.getInteger("version", 0));
        changeSetEntity.setLowerLeftX(document.getDouble("lower_left_x"));
        changeSetEntity.setLowerLeftY(document.getDouble("lower_left_y"));
        changeSetEntity.setUpperRightX(document.getDouble("upper_right_x"));
        changeSetEntity.setUpperRightY(document.getDouble("upper_right_y"));
        changeSetEntity.setLineHash(document.getString("line_hash"));

        final String tokenEncryptedKey = document.getString("token_encrypted_key");
        changeSetEntity.token = encryptionService.decrypt(document.getString("token"), tokenEncryptedKey);
        changeSetEntity.setTokenEncryptedKey(tokenEncryptedKey);

        final String replacementEncryptedKey = document.getString("replacement_encrypted_key");
        changeSetEntity.replacement = encryptionService.decrypt(document.getString("replacement"), replacementEncryptedKey);
        changeSetEntity.setReplacementEncryptedKey(replacementEncryptedKey);

        changeSetEntity.setUuid(document.getString("uuid"));

        return changeSetEntity;

    }

    // Duplicate the changeset entity.
    public static ChangeSetEntity duplicate(final ChangeSetEntity changeSetEntity, final int newVersion) {
        final ChangeSetEntity duplicateChangeSetEntity = new ChangeSetEntity();
        duplicateChangeSetEntity.setUserId(changeSetEntity.getUserId());
        duplicateChangeSetEntity.setDocumentId(changeSetEntity.getDocumentId());
        duplicateChangeSetEntity.setToken(changeSetEntity.getToken());
        duplicateChangeSetEntity.setTokenEncryptedKey(changeSetEntity.getTokenEncryptedKey());
        duplicateChangeSetEntity.setCharacterStart(changeSetEntity.getCharacterStart());
        duplicateChangeSetEntity.setCharacterEnd(changeSetEntity.getCharacterEnd());
        duplicateChangeSetEntity.setReplacement(changeSetEntity.getReplacement());
        duplicateChangeSetEntity.setReplacementEncryptedKey(changeSetEntity.getReplacementEncryptedKey());
        duplicateChangeSetEntity.setType(changeSetEntity.getType());
        duplicateChangeSetEntity.setConfidence(changeSetEntity.getConfidence());
        duplicateChangeSetEntity.setParagraph(changeSetEntity.getParagraph());
        duplicateChangeSetEntity.setPage(changeSetEntity.getPage());
        duplicateChangeSetEntity.setLineNumber(changeSetEntity.getLineNumber());
        duplicateChangeSetEntity.setVersion(newVersion);
        duplicateChangeSetEntity.setLowerLeftX(changeSetEntity.getLowerLeftX());
        duplicateChangeSetEntity.setLowerLeftY(changeSetEntity.getLowerLeftY());
        duplicateChangeSetEntity.setUpperRightX(changeSetEntity.getUpperRightX());
        duplicateChangeSetEntity.setUpperRightY(changeSetEntity.getUpperRightY());
        duplicateChangeSetEntity.setLineHash(changeSetEntity.getLineHash());
        duplicateChangeSetEntity.setUuid(changeSetEntity.getUuid());
        return duplicateChangeSetEntity;
    }

    @Override
    public Document toDocument(final EncryptionService encryptionService) {

        final Document document = new Document();

        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        document.put("document_id", documentId);
        document.put("character_start", characterStart);
        document.put("character_end", characterEnd);
        document.put("type", type);
        document.put("confidence", confidence);
        document.put("paragraph", paragraph);
        document.put("page", page);
        document.put("line_number", lineNumber);
        document.put("version", version);
        document.put("lower_left_x", lowerLeftX);
        document.put("lower_left_y", lowerLeftY);
        document.put("upper_right_x", upperRightX);
        document.put("upper_right_y", upperRightY);
        document.put("line_hash", lineHash);

        final EncryptResult tokenEncryptResult = encryptionService.encrypt(token, userId.toHexString());
        document.put("token", tokenEncryptResult.getEncryptedText());
        document.put("token_encrypted_key", tokenEncryptResult.getEncryptionKey());

        final EncryptResult replacementEncryptResult = encryptionService.encrypt(replacement, userId.toHexString());
        document.put("replacement", replacementEncryptResult.getEncryptedText());
        document.put("replacement_encrypted_key", replacementEncryptResult.getEncryptionKey());

        document.put("uuid", uuid);

        return document;

    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public ObjectId getUserId() {
        return userId;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
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

    public int getCharacterStart() {
        return characterStart;
    }

    public void setCharacterStart(int characterStart) {
        this.characterStart = characterStart;
    }

    public int getCharacterEnd() {
        return characterEnd;
    }

    public void setCharacterEnd(int characterEnd) {
        this.characterEnd = characterEnd;
    }

    public String getReplacement() {
        return replacement;
    }

    public void setReplacement(String replacement) {
        this.replacement = replacement;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public int getParagraph() {
        return paragraph;
    }

    public void setParagraph(int paragraph) {
        this.paragraph = paragraph;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public double getLowerLeftX() {
        return lowerLeftX;
    }

    public void setLowerLeftX(double lowerLeftX) {
        this.lowerLeftX = lowerLeftX;
    }

    public double getLowerLeftY() {
        return lowerLeftY;
    }

    public void setLowerLeftY(double lowerLeftY) {
        this.lowerLeftY = lowerLeftY;
    }

    public double getUpperRightX() {
        return upperRightX;
    }

    public void setUpperRightX(double upperRightX) {
        this.upperRightX = upperRightX;
    }

    public double getUpperRightY() {
        return upperRightY;
    }

    public void setUpperRightY(double upperRightY) {
        this.upperRightY = upperRightY;
    }

    public String getLineHash() {
        return lineHash;
    }

    public void setLineHash(String lineHash) {
        this.lineHash = lineHash;
    }

    public String getTokenEncryptedKey() {
        return tokenEncryptedKey;
    }

    public void setTokenEncryptedKey(String tokenEncryptedKey) {
        this.tokenEncryptedKey = tokenEncryptedKey;
    }

    public String getReplacementEncryptedKey() {
        return replacementEncryptedKey;
    }

    public void setReplacementEncryptedKey(String replacementEncryptedKey) {
        this.replacementEncryptedKey = replacementEncryptedKey;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }
}