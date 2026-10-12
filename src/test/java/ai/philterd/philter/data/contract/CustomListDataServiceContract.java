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
package ai.philterd.philter.data.contract;

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.model.ServiceResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link CustomListDataService}. */
public interface CustomListDataServiceContract extends StorageContract {

    private void createList(final String owner, final String name, final List<String> items) {
        final ServiceResponse created = storage().customLists().saveOrUpdate("req", owner, name, null, items, false, "test");
        assertTrue(created.isSuccessful(), created.getMessage());
    }

    @Test
    default void customListNamesAreUniquePerUser() {
        final String owner = RecordIds.newId();
        createList(owner, "names", List.of("Jordan"));
        final ServiceResponse again = storage().customLists().saveOrUpdate("req", owner, "names", null, List.of("Riley"),
                false, "test");
        assertFalse(again.isSuccessful());
        assertEquals(List.of("Jordan"), storage().customLists().findOneByName("names", owner).getItems());
        createList(RecordIds.newId(), "names", List.of("Riley"));
    }

    @Test
    default void customListItemsReadBackUnchanged() {
        final String owner = RecordIds.newId();
        final List<String> items = List.of("Ünïcödé", "with, comma", "\"quoted\"", "tab\there");
        createList(owner, "items", items);
        assertEquals(items, storage().customLists().findOneByName("items", owner).getItems());
    }

    @Test
    default void customListListingsCountPageAndSearchAsPlainText() {
        final String owner = RecordIds.newId();
        for (final String name : List.of("a.b", "axb", "A.B-2", "other", "more")) {
            createList(owner, name, List.of("x"));
        }
        final Listings.Sort byName = new Listings.Sort("name", false);
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            final Listings.Page<CustomListEntity> page = storage().customLists().list(owner, null, byName, offset, 2);
            assertEquals(5, page.total());
            page.items().forEach(list -> assertTrue(seen.add(list.getName())));
        }
        assertEquals(5, seen.size());
        // "." matches only a dot, and case is ignored.
        final Listings.Page<CustomListEntity> found = storage().customLists().list(owner, "a.b", byName, 0, 10);
        assertEquals(Set.of("a.b", "A.B-2"), Set.copyOf(found.items().stream().map(CustomListEntity::getName).toList()));
        assertEquals(2, found.total());
    }

}
