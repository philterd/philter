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
import ai.philterd.philter.utils.PathSafeNames;

import java.util.List;

/**
 * The rules for custom lists, the same in every storage implementation: what a valid list is, when a write is
 * refused, and what is audited. An implementation stores and reads the lists.
 */
public abstract class StoredCustomListDataService implements CustomListDataService {

    protected final AuditEventPublisher auditEventPublisher;

    protected StoredCustomListDataService(final AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Stores a new list, its items encrypted, and returns its id.
     *
     * @throws DuplicateRecordException when the owner already has a list with its name.
     */
    protected abstract String insert(final CustomListEntity list);

    /** Replaces the stored list that has the same id. */
    protected abstract void update(final CustomListEntity list);

    @Override
    public ServiceResponse replace(final String requestId, final String userId, final String listName,
                                   final String description, final List<String> listItems, final String origin) {
        return write(requestId, userId, listName, description, listItems, true, false, origin);
    }

    @Override
    public ServiceResponse saveOrUpdate(final String requestId, final String userId, final String listName, final String description, final List<String> listItems, final boolean allowUpdate, final String origin) {
        return write(requestId, userId, listName, description, listItems, allowUpdate, true, origin);
    }

    private ServiceResponse write(final String requestId, final String userId, final String listName,
                                  final String description, final List<String> listItems, final boolean allowUpdate,
                                  final boolean allowCreate, final String origin) {

        if(listItems == null) {
            return new ServiceResponse("List items cannot be empty.", false, 400);
        }

        if (listName == null || listName.isEmpty()) {
            return new ServiceResponse("List name cannot be empty.", false, 400);
        }

        if (!PathSafeNames.isPathSafe(listName)) {
            return new ServiceResponse("The list name " + PathSafeNames.RULE + ".", false, 400);
        }

        if (listItems.size() > CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS) {
            return new ServiceResponse("List contains too many items. Maximum is " + CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS + ".", false, 400);
        }

        // Filtered into a new list rather than removed from the caller's: removeIf mutates the
        // argument, and throws outright on the immutable list any modern caller would pass.
        final List<String> trimmedListItems = listItems.stream()
                .filter(item -> item != null && !item.isEmpty())
                .map(String::trim)
                .toList();

        if(trimmedListItems.isEmpty()) {
            return new ServiceResponse("List items cannot be empty.", false, 400);
        }

        // Check if any item exceeds the maximum length
        for (final String item : trimmedListItems) {
            if (item.length() > MAXIMUM_ITEM_LENGTH) {
                return new ServiceResponse("A list item is too long. List items cannot be longer than " + MAXIMUM_ITEM_LENGTH + " characters.", false, 400);
            }
        }

        final CustomListEntity existing = findOneByName(listName, userId);

        if(existing != null) {

            if(allowUpdate) {

                // If it does already exist, we are just going to update it's content.
                existing.setItems(trimmedListItems);
                // Left out, the description stays as it is; an empty one clears it.
                if (description != null) {
                    existing.setDescription(description);
                }
                update(existing);

                auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_UPDATED, existing.getId(), origin);

                return new ServiceResponse("List was updated", true, 200);

            } else {

                // Not allowed to update the existing list.
                return new ServiceResponse("A list with this name already exists.", false, 409, REASON_LIST_EXISTS);

            }

        } else if (!allowCreate) {

            return new ServiceResponse("List does not exist.", false, 404);

        } else {

            final CustomListEntity customListEntity = new CustomListEntity();
            customListEntity.setUserId(userId);
            customListEntity.setName(listName);
            customListEntity.setDescription(description == null ? "" : description);
            customListEntity.setItems(trimmedListItems);
            final String id;
            try {
                id = insert(customListEntity);
            } catch (final DuplicateRecordException race) {
                // A concurrent create took the name after the check above.
                return new ServiceResponse("A list with this name already exists.", false, 409, REASON_LIST_EXISTS);
            }

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_CREATED, id, origin);

            return new ServiceResponse("List created", true, id, 201);

        }

    }

}
