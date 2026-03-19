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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CustomListEntity extends AbstractEncryptedEntity {

    private ObjectId id;
    private String name;
    private String description;
    private List<String> items;
    private String itemsEncryptedKey;

    public static CustomListEntity fromDocument(final Document document, final EncryptionService encryptionService) {
        final CustomListEntity customListEntity = new CustomListEntity();
        customListEntity.setId(document.getObjectId("_id"));
        customListEntity.setName(document.getString("name"));
        customListEntity.setDescription(document.getString("description"));
        customListEntity.setUserId(document.getObjectId("user_id"));
        
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

    @Override
    public Document toDocument(final EncryptionService encryptionService) {
        final Document document = new Document();
        if(id != null) {
            document.put("_id", id);
        }
        document.put("name", name);
        document.put("description", description);
        document.put("user_id", userId);
        
        if (items != null && !items.isEmpty()) {
            final String itemsStr = String.join("\n", items);
            final EncryptResult encryptResult = encryptionService.encrypt(itemsStr, userId.toHexString());
            document.put("items", encryptResult.getEncryptedText());
            document.put("items_encrypted_key", encryptResult.getEncryptionKey());
        } else {
            document.put("items", "");
            document.put("items_encrypted_key", "");
        }
        
        return document;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public void setUserId(ObjectId userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getItems() {
        return items;
    }

    public void setItems(List<String> items) {
        this.items = items;
    }

    public String getItemsEncryptedKey() {
        return itemsEncryptedKey;
    }

    public void setItemsEncryptedKey(String itemsEncryptedKey) {
        this.itemsEncryptedKey = itemsEncryptedKey;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}
