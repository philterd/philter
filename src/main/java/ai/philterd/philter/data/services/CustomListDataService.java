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

import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.model.ServiceResponse;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Each user's custom lists of terms, stored encrypted. {@link StoredCustomListDataService} holds the rules every
 * implementation shares.
 *
 * <p>Guarantees every implementation must provide:
 * <ul>
 *   <li>A list name is unique per user: creating a second list with the same name is refused and writes
 *   nothing.</li>
 *   <li>Items are stored encrypted, and what is written is read back unchanged.</li>
 *   <li>Listings return their total, page by offset without repeating or skipping lists, sort by name, and
 *   search names as case-insensitive plain text (see {@link Listings}).</li>
 * </ul>
 */
public interface CustomListDataService {

    int MAXIMUM_NUMBER_OF_ITEMS = 100;

    int MAXIMUM_ITEM_LENGTH = 50;

    int MAXIMUM_DESCRIPTION_LENGTH = 200;

    /** Why a create is refused with 409, carried in the response's details. */
    String REASON_LIST_EXISTS = "list_exists";

    /** Replaces an existing list's items, and its description when one is given; 404 if there is no such list. */
    ServiceResponse replace(final String requestId, final String userId, final String listName,
            final String description, final List<String> listItems, final String origin);

    ServiceResponse saveOrUpdate(final String requestId, final String userId, final String listName, final String description, final List<String> listItems, final boolean allowUpdate, final String origin);

    /**
     * Fetches several custom lists for a user in a single query, returning a map of list name to its
     * items. Used to resolve all {@code list:} references in a policy at once rather than issuing one
     * query per reference. Names with no matching list are simply absent from the returned map.
     */
    Map<String, List<String>> findItemsByNames(final String userId, final Collection<String> names);

    CustomListEntity findOneByName(final String name, final String userId);

    /**
     * A page of the user's custom lists, or every user's when {@code userId} is null, whose names contain
     * {@code q}, and their total.
     */
    Listings.Page<CustomListEntity> list(final String userId, final String q, final Listings.Sort sort,
            final int offset, final int limit);

    void deleteByName(final String name, final String userId);

}
