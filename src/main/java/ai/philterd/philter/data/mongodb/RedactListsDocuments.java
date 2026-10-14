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

import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.services.SecretFields;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;

import java.util.List;

/** Maps {@link RedactListsEntity} to and from the documents MongoDB stores. */
public final class RedactListsDocuments {

    private RedactListsDocuments() {
    }

    public static RedactListsEntity fromDocument(final Document document, final EncryptionService encryptionService) {

        final RedactListsEntity redactListsEntity = new RedactListsEntity();
        redactListsEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        redactListsEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));

        redactListsEntity.setTermsToAlwaysRedact(readTerms(document, encryptionService, "terms_to_always_redact"));
        redactListsEntity.setTermsToNeverRedact(readTerms(document, encryptionService, "terms_to_never_redact"));
        redactListsEntity.setAlwaysRedactRevision(readRevision(document, "always_redact_revision"));
        redactListsEntity.setNeverRedactRevision(readRevision(document, "never_redact_revision"));

        return redactListsEntity;
    }

    public static Document toDocument(final RedactListsEntity entity, final EncryptionService encryptionService) {
        final Document document = new Document();
        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("user_id", MongoIds.objectId(entity.getUserId()));
        writeTerms(document, encryptionService, entity.getUserId(), "terms_to_always_redact", entity.getTermsToAlwaysRedact());
        writeTerms(document, encryptionService, entity.getUserId(), "terms_to_never_redact", entity.getTermsToNeverRedact());
        return document;
    }

    /** Reads a term list stored by {@link #writeTerms}. */
    private static List<String> readTerms(final Document document, final EncryptionService encryptionService, final String field) {
        return SecretFields.openLines(encryptionService, document.getString(field), document.getString(field + "_key"));
    }

    /** A list's revision, which counts its changes. A document written before revisions existed is at 0. */
    static long readRevision(final Document document, final String field) {
        final Object raw = document.get(field);
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    /** Encrypts a term list and stores it as {@code <field>} (ciphertext) plus the key {@code <field>_key}. */
    public static void writeTerms(final Document document, final EncryptionService encryptionService, final String userId,
                                  final String field, final List<String> terms) {
        final SecretFields.Sealed sealed = SecretFields.sealLines(encryptionService, terms, userId);
        document.put(field, sealed.ciphertext());
        document.put(field + "_key", sealed.key());
    }

}
