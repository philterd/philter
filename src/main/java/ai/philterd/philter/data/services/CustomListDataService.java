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
package ai.philterd.philter.data.services;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.encryption.EncryptionService;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Filters;
import com.mongodb.client.result.DeleteResult;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class CustomListDataService extends AbstractEncryptedService<CustomListEntity> {

    public static final int MAXIMUM_NUMBER_OF_LISTS = 25;
    public static final int MAXIMUM_NUMBER_OF_ITEMS = 100;
    public static final int MAXIMUM_ITEM_LENGTH = 50;
    public static final int MAX_LIMIT = 100;

    public CustomListDataService(final MongoClient mongoClient, final EncryptionService encryptionService, final AuditEventPublisher auditEventPublisher) {
        super(mongoClient, "custom_lists", encryptionService, auditEventPublisher);
    }

    public ServiceResponse saveOrUpdate(final String requestId, final String listName, final String description, final List<String> listItems, final boolean allowUpdate, final String origin) {

        if(listItems == null) {
            return new ServiceResponse("List items cannot be empty.", false, 400);
        }

        if (listName == null || listName.isEmpty()) {
            return new ServiceResponse("List name cannot be empty.", false, 400);
        }

        if (listItems.size() > CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS) {
            return new ServiceResponse("List contains too many items. Maximum is " + CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS + ".", false, 400);
        }
        
        // Remove all blank lines from the list.
        listItems.removeIf(item -> item == null || item.isEmpty());
        final List<String> trimmedListItems = listItems.stream().map(String::trim).toList();

        if(trimmedListItems.isEmpty()) {
            return new ServiceResponse("List items cannot be empty.", false, 400);
        }

        // Check if any item exceeds the maximum length
        for (final String item : trimmedListItems) {
            if (item.length() > MAXIMUM_ITEM_LENGTH) {
                return new ServiceResponse("A list item is too long. List items cannot be longer than " + MAXIMUM_ITEM_LENGTH + " characters.", false, 400);
            }
        }

        if(existsForUser(listName)) {

            if(allowUpdate) {

                // Is updating the existing list ok?

                final CustomListEntity customListEntity = findOneByName(listName);

                // If it does already exist, we are just going to update it's content.
                customListEntity.setItems(trimmedListItems);
                customListEntity.setDescription(description);
                update(customListEntity);

                auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_UPDATED, customListEntity.getId(), origin);

                return new ServiceResponse("List was updated", true, 200);

            } else {

                // Not allowed to update the existing list.
                return new ServiceResponse("A list with this name already exists.", false, 409);

            }

        } else {

            final CustomListEntity customListEntity = new CustomListEntity();
            customListEntity.setName(listName);
            customListEntity.setDescription(description);
            customListEntity.setItems(trimmedListItems);
            final ObjectId objectId = save(customListEntity);

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_CREATED, objectId, origin);

            return new ServiceResponse("List created", true, objectId, 201);

        }

    }

    public CustomListEntity findOneById(final ObjectId id) {

        final Bson filter1 = Filters.eq("_id", id);

        final Document document = collection.find(filter1).first();

        if(document != null) {
            return CustomListEntity.fromDocument(document, encryptionService);
        } else {
            return null;
        }

    }

    public CustomListEntity findOneByName(final String name) {

        final Bson filter1 = Filters.eq("name", name);

        final Document document = collection.find(filter1).first();

        if(document != null) {
            return CustomListEntity.fromDocument(document, encryptionService);
        } else {
            return null;
        }

    }

    public boolean existsForUser(final String name) {

        final Bson filter1 = Filters.eq("name", name);

        final Document document = collection.find(filter1).first();

        return document != null;

    }

    public List<CustomListEntity> findAll() {

        final FindIterable<Document> documents = collection.find();

        final List<CustomListEntity> customListEntities = new ArrayList<>();

        for(final Document document : documents) {
            customListEntities.add(CustomListEntity.fromDocument(document, encryptionService));
        }

        return customListEntities;

    }

    public List<CustomListEntity> findBySearchTerm(final String searchTerm, final int limit) {

        final int effectiveLimit = Math.min(limit, MAX_LIMIT);
        final Pattern pattern = Pattern.compile(".*" + Pattern.quote(searchTerm) + ".*", Pattern.CASE_INSENSITIVE);

        final Bson query = Filters.and(
                Filters.or(
                        Filters.regex("name", pattern),
                        Filters.regex("description", pattern)
                )
        );

        final FindIterable<Document> documents = collection.find(query).limit(effectiveLimit);

        final List<CustomListEntity> customListEntities = new ArrayList<>();

        for(final Document document : documents) {
            customListEntities.add(CustomListEntity.fromDocument(document, encryptionService));
        }

        return customListEntities;

    }

    public List<CustomListEntity> findAll(final int offset, final int limit) {
        return findAll(offset, limit, null, null);
    }

    public List<CustomListEntity> findAll(final int offset, final int limit, final String sortField, final String sortDirection) {

        // Apply sorting if specified
        final Document sortDocument = new Document();
        if (sortField != null && !sortField.isBlank()) {
            // Validate sortField against a whitelist to prevent NoSQL injection
            final String validatedSortField = switch (sortField) {
                case "name", "description" -> sortField;
                default -> "name"; // Fallback to default if invalid field is provided
            };
            final int direction = "DESC".equalsIgnoreCase(sortDirection) ? -1 : 1;
            sortDocument.append(validatedSortField, direction);
        } else {
            // Default sort by name ascending
            sortDocument.append("name", 1);
        }

        final FindIterable<Document> documents = collection.find()
                .sort(sortDocument)
                .skip(offset)
                .limit(limit);

        final List<CustomListEntity> customListEntities = new ArrayList<>();

        for(final Document document : documents) {
            customListEntities.add(CustomListEntity.fromDocument(document, encryptionService));
        }

        return customListEntities;

    }

    public int count() {

        final long count = collection.countDocuments();

        return (int) count;

    }

    public void deleteByName(final String name) {

        final Document filter = new Document("name", name);
        collection.deleteOne(filter);

    }

    public long deleteAll(final ObjectId userId) {

        final Document query = new Document("user_id", userId);

        final DeleteResult deleteResult = collection.deleteMany(query);

        return deleteResult.getDeletedCount();

    }

}
