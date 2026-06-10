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

import ai.philterd.philter.model.SeparatedTermLists;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RedactListsEntity extends AbstractEncryptedEntity {

    private ObjectId id;
    private List<String> termsToAlwaysRedact;
    private List<String> termsToNeverRedact;
    private ObjectId userId;

    public static RedactListsEntity fromDocument(final Document document, final EncryptionService encryptionService) {

        final RedactListsEntity redactListsEntity = new RedactListsEntity();
        redactListsEntity.setId(document.getObjectId("_id"));
        redactListsEntity.setUserId(document.getObjectId("user_id"));

        redactListsEntity.setTermsToAlwaysRedact(readTerms(document, encryptionService, "terms_to_always_redact"));
        redactListsEntity.setTermsToNeverRedact(readTerms(document, encryptionService, "terms_to_never_redact"));

        return redactListsEntity;
    }

    /**
     * Reads a term list, decrypting the encrypted form (a ciphertext string plus its {@code <field>_key}).
     * Falls back to the legacy plaintext array for documents written before these lists were encrypted;
     * such documents are re-written encrypted on the next save.
     */
    private static List<String> readTerms(final Document document, final EncryptionService encryptionService, final String field) {
        final Object raw = document.get(field);
        final List<String> terms = new ArrayList<>();
        if (raw instanceof String) {
            final String encrypted = (String) raw;
            final String key = document.getString(field + "_key");
            if (!encrypted.isEmpty() && key != null) {
                final String decrypted = encryptionService.decrypt(encrypted, key);
                if (decrypted != null && !decrypted.isEmpty()) {
                    terms.addAll(Arrays.asList(decrypted.split("\n")));
                }
            }
        } else if (raw instanceof List) {
            // Legacy plaintext list, written before these lists were encrypted.
            terms.addAll(document.getList(field, String.class));
        }
        return terms;
    }

    public SeparatedTermLists breakAlwaysRedactIntoSeparateLists() {

        final List<String> fuzzy = new ArrayList<>();
        final List<String> exact = new ArrayList<>();

        for(final String term : termsToAlwaysRedact) {

            if(term.endsWith(":fuzzy")) {
                fuzzy.add(term.substring(0, term.length() - 6));
            } else {
                exact.add(term);
            }

        }

        return new SeparatedTermLists(fuzzy, exact);

    }

    @Override
    public Document toDocument(final EncryptionService encryptionService) {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("user_id", userId);
        writeTerms(document, encryptionService, "terms_to_always_redact", termsToAlwaysRedact);
        writeTerms(document, encryptionService, "terms_to_never_redact", termsToNeverRedact);
        return document;
    }

    /** Encrypts a term list and stores it as {@code <field>} (ciphertext) plus the key {@code <field>_key}. */
    private void writeTerms(final Document document, final EncryptionService encryptionService, final String field, final List<String> terms) {
        final String joined = String.join("\n", terms != null ? terms : new ArrayList<>());
        if (joined.isEmpty()) {
            document.put(field, "");
            document.put(field + "_key", "");
        } else {
            final EncryptResult result = encryptionService.encrypt(joined, userId.toHexString());
            document.put(field, result.getEncryptedText());
            document.put(field + "_key", result.getEncryptionKey());
        }
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public void setTermsToAlwaysRedact(List<String> termsToAlwaysRedact) {
        this.termsToAlwaysRedact = termsToAlwaysRedact;
    }

    public void setTermsToNeverRedact(List<String> termsToNeverRedact) {
        this.termsToNeverRedact = termsToNeverRedact;
    }

    public List<String> getTermsToAlwaysRedact() {
        return termsToAlwaysRedact;
    }

    public List<String> getTermsToNeverRedact() {
        return termsToNeverRedact;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public ObjectId getUserId() {
        return userId;
    }

}