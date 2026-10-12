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

import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.services.encryption.EncryptResult;
import ai.philterd.philter.services.encryption.EncryptionService;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maps {@link CustomListEntity} to and from the documents MongoDB stores. */
public final class CustomListDocuments {

    private CustomListDocuments() {
    }

    public static CustomListEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final CustomListEntity customListEntity = new CustomListEntity();
        customListEntity.setId(MongoIds.hex(document.getObjectId("_id")));
        customListEntity.setName(document.getString("name"));
        customListEntity.setDescription(document.getString("description"));
        customListEntity.setUserId(MongoIds.hex(document.getObjectId("user_id")));

        final String encryptedItems = document.getString("items");
        final String itemsEncryptedKey = document.getString("items_encrypted_key");

        final List<String> decryptedItems = new ArrayList<>();
        if (encryptedItems != null && itemsEncryptedKey != null) {
            final String decryptedItemsStr = encryptionService.decrypt(encryptedItems, itemsEncryptedKey);
            if (decryptedItemsStr != null && !decryptedItemsStr.isEmpty()) {
                decryptedItems.addAll(Arrays.asList(decryptedItemsStr.split("\n")));
            }
        }

        customListEntity.setItems(decryptedItems);
        customListEntity.setItemsEncryptedKey(itemsEncryptedKey);

        return customListEntity;
    }

    public static Document toDocument(final CustomListEntity entity, final EncryptionService encryptionService) {
        final Document document = new Document();
        if(entity.getId() != null) {
            document.put("_id", MongoIds.objectId(entity.getId()));
        }
        document.put("name", entity.getName());
        document.put("description", entity.getDescription());
        document.put("user_id", MongoIds.objectId(entity.getUserId()));

        if (entity.getItems() != null && !entity.getItems().isEmpty()) {
            final String itemsStr = String.join("\n", entity.getItems());
            final EncryptResult encryptResult = encryptionService.encrypt(itemsStr, entity.getUserId());
            document.put("items", encryptResult.getEncryptedText());
            document.put("items_encrypted_key", encryptResult.getEncryptionKey());
        } else {
            document.put("items", "");
            document.put("items_encrypted_key", "");
        }

        return document;
    }

}
