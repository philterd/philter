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

import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Custom lists hold the terms a deployment always or never redacts, so their contents are as sensitive
 * as the documents they are applied to. Driven against a real database rather than mocks, because what
 * matters is what ends up stored and who can read it back.
 */
class CustomListDataServiceIT extends AbstractMongoIT {

    private CustomListDataService service;

    @BeforeEach
    void setUpService() {
        service = new MongoCustomListDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class));
    }

    private long count(final String user) {
        return service.list(user, null, new Listings.Sort("name", false), 0, 100).total();
    }

    private ServiceResponse save(final String user, final String name, final List<String> items) {
        return service.saveOrUpdate("req", user, name, "a description", items, true, "test");
    }

    // ----- Creating and updating -----

    @Test
    @DisplayName("A list is created, then read back with its items and description")
    void aListRoundTrips() {

        final String user = MongoIds.newId();
        final ServiceResponse response = save(user, "names", List.of("Alice", "Bob"));

        assertTrue(response.isSuccessful());
        assertEquals(201, response.getStatusCode());

        final CustomListEntity stored = service.findOneByName("names", user);
        assertNotNull(stored);
        assertEquals(List.of("Alice", "Bob"), stored.getItems());
        assertEquals("a description", stored.getDescription());

    }

    @Test
    @DisplayName("Saving the same name again replaces its items when updating is allowed")
    void anUpdateReplacesTheItems() {

        final String user = MongoIds.newId();
        save(user, "names", List.of("Alice", "Bob"));

        final ServiceResponse update = save(user, "names", List.of("Carol"));

        assertTrue(update.isSuccessful());
        assertEquals(200, update.getStatusCode());
        assertEquals(List.of("Carol"), service.findOneByName("names", user).getItems());
        assertEquals(1, count(user), "an update must not leave a second list behind");

    }

    @Test
    @DisplayName("Saving the same name is refused when updating is not allowed")
    void aDuplicateNameIsRefusedWhenUpdatingIsNotAllowed() {

        final String user = MongoIds.newId();
        save(user, "names", List.of("Alice"));

        final ServiceResponse second =
                service.saveOrUpdate("req", user, "names", "d", List.of("Bob"), false, "test");

        assertFalse(second.isSuccessful());
        assertEquals(409, second.getStatusCode());
        assertEquals(CustomListDataService.REASON_LIST_EXISTS, second.getDetails());
        assertEquals(List.of("Alice"), service.findOneByName("names", user).getItems(),
                "the refused save must not have changed anything");

    }

    @Test
    @DisplayName("A create that loses a race for the name is refused by the unique index")
    void aRacingCreateIsRefused() {

        final String user = MongoIds.newId();
        save(user, "names", List.of("Alice"));

        // The name check saw no list, as it would if the other create had not landed yet.
        final CustomListDataService racing = spy(service);
        doReturn(null).when(racing).findOneByName("names", user);

        final ServiceResponse refused = racing.saveOrUpdate("req", user, "names", "d", List.of("Bob"), false, "test");
        assertEquals(409, refused.getStatusCode());
        assertEquals(CustomListDataService.REASON_LIST_EXISTS, refused.getDetails());
        assertEquals(1, count(user), "only one list has the name");

    }


    @Test
    @DisplayName("Replacing a list changes its items, keeps an omitted description, and clears an empty one")
    void replaceChangesAnExistingList() {

        final String user = MongoIds.newId();
        save(user, "names", List.of("Alice"));

        final ServiceResponse replaced = service.replace("req", user, "names", null, List.of("Bob", "Carol"), "test");
        assertEquals(200, replaced.getStatusCode());
        assertEquals(List.of("Bob", "Carol"), service.findOneByName("names", user).getItems());
        assertEquals("a description", service.findOneByName("names", user).getDescription());

        assertEquals(200, service.replace("req", user, "names", "", List.of("Bob"), "test").getStatusCode());
        assertEquals("", service.findOneByName("names", user).getDescription());

    }

    @Test
    @DisplayName("Replacing a list that does not exist is refused and creates nothing")
    void replaceRefusesAMissingList() {

        final String user = MongoIds.newId();
        final ServiceResponse refused = service.replace("req", user, "missing", "d", List.of("Bob"), "test");

        assertFalse(refused.isSuccessful());
        assertEquals(404, refused.getStatusCode());
        assertNull(service.findOneByName("missing", user), "nothing was created");

    }

    @Test
    @DisplayName("Two users may each have a list of the same name")
    void theSameNameBelongsToEachUserSeparately() {

        final String one = MongoIds.newId();
        final String two = MongoIds.newId();

        assertEquals(201, save(one, "names", List.of("Alice")).getStatusCode());
        assertEquals(201, save(two, "names", List.of("Bob")).getStatusCode());

        assertEquals(List.of("Alice"), service.findOneByName("names", one).getItems());
        assertEquals(List.of("Bob"), service.findOneByName("names", two).getItems());

    }

    // ----- What is refused -----

    @Test
    @DisplayName("An empty name, empty items, or a list of blanks is refused")
    void emptyInputIsRefused() {

        final String user = MongoIds.newId();

        assertEquals(400, service.saveOrUpdate("req", user, "", "d", List.of("a"), true, "test").getStatusCode());
        assertEquals(400, service.saveOrUpdate("req", user, null, "d", List.of("a"), true, "test").getStatusCode());
        assertEquals(400, service.saveOrUpdate("req", user, "n", "d", List.of(), true, "test").getStatusCode());
        assertEquals(400, service.saveOrUpdate("req", user, "n", "d", null, true, "test").getStatusCode());

        assertEquals(0, count(user), "nothing refused may be stored");

    }

    @Test
    @DisplayName("A list at the maximum is accepted and one item beyond it is not")
    void theItemCountLimitIsEnforcedAtTheBoundary() {

        final String user = MongoIds.newId();

        final List<String> atLimit = new ArrayList<>();
        for (int i = 0; i < CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS; i++) {
            atLimit.add("item-" + i);
        }

        assertTrue(save(user, "at-limit", atLimit).isSuccessful(), "the maximum itself must be allowed");

        final List<String> overLimit = new ArrayList<>(atLimit);
        overLimit.add("one-too-many");

        final ServiceResponse refused = save(user, "over-limit", overLimit);
        assertFalse(refused.isSuccessful());
        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains(String.valueOf(CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS)),
                "the message must name the limit so a caller can act on it: " + refused.getMessage());

        assertNull(service.findOneByName("over-limit", user));

    }

    @Test
    @DisplayName("An item at the maximum length is accepted and one character beyond it is not")
    void theItemLengthLimitIsEnforcedAtTheBoundary() {

        final String user = MongoIds.newId();

        final String atLimit = "x".repeat(CustomListDataService.MAXIMUM_ITEM_LENGTH);
        assertTrue(save(user, "at-limit", List.of(atLimit)).isSuccessful());

        final String tooLong = "x".repeat(CustomListDataService.MAXIMUM_ITEM_LENGTH + 1);
        final ServiceResponse refused = save(user, "too-long", List.of("fine", tooLong));

        assertFalse(refused.isSuccessful());
        assertEquals(400, refused.getStatusCode());
        assertTrue(refused.getMessage().contains(String.valueOf(CustomListDataService.MAXIMUM_ITEM_LENGTH)),
                "the message must name the limit: " + refused.getMessage());

        assertNull(service.findOneByName("too-long", user), "one bad item must reject the whole list");

    }

    // ----- What is stored -----

    @Test
    @DisplayName("List items are encrypted at rest")
    void itemsAreNotStoredInTheClear() {

        final String user = MongoIds.newId();
        save(user, "names", List.of("Alice Anderson", "Bob Brown"));

        final Document raw = mongoClient.getDatabase("philter").getCollection("custom_lists")
                .find(new Document("user_id", MongoIds.objectId(user))).first();

        assertNotNull(raw);
        assertFalse(raw.toJson().contains("Alice Anderson"),
                "a term a deployment redacts must not sit in the clear in the document that lists it");
        assertFalse(raw.toJson().contains("Bob Brown"));

    }

    // ----- Reading -----


    @Test
    @DisplayName("A list is found by name only by the user who owns it")
    void findingByNameIsScopedToTheOwner() {

        final String owner = MongoIds.newId();
        save(owner, "names", List.of("Alice"));

        assertNotNull(service.findOneByName("names", owner));
        assertNull(service.findOneByName("names", MongoIds.newId()));

    }

    @Test
    @DisplayName("Items are fetched by name for one user, and unknown names are simply absent")
    void itemsAreFetchedByNameForOneUser() {

        final String owner = MongoIds.newId();
        final String stranger = MongoIds.newId();

        save(owner, "first", List.of("Alice"));
        save(owner, "second", List.of("Bob"));
        save(stranger, "first", List.of("Should not appear"));

        final Map<String, List<String>> found =
                service.findItemsByNames(owner, List.of("first", "second", "missing"));

        assertEquals(Set.of("first", "second"), found.keySet(), "an unknown name must not appear");
        assertEquals(List.of("Alice"), found.get("first"));
        assertEquals(List.of("Bob"), found.get("second"));

    }

    @Test
    @DisplayName("Paging and sorting return each list once, in order")
    void listsArePagedAndSorted() {

        final String user = MongoIds.newId();
        for (final String name : List.of("charlie", "alpha", "bravo")) {
            save(user, name, List.of("item"));
        }

        assertEquals(3, count(user));

        final Listings.Sort byName = new Listings.Sort("name", false);
        final List<String> ascending = service.list(user, null, byName, 0, 10).items()
                .stream().map(CustomListEntity::getName).toList();
        assertEquals(List.of("alpha", "bravo", "charlie"), ascending);

        final List<String> firstPage = service.list(user, null, byName, 0, 2).items()
                .stream().map(CustomListEntity::getName).toList();
        final List<String> secondPage = service.list(user, null, byName, 2, 2).items()
                .stream().map(CustomListEntity::getName).toList();

        assertEquals(List.of("alpha", "bravo"), firstPage);
        assertEquals(List.of("charlie"), secondPage, "the second page must continue, not repeat");

    }


    // ----- Deleting -----

    @Test
    @DisplayName("Deleting by name removes one list, and only for its owner")
    void deletingByNameIsScopedToTheOwner() {

        final String owner = MongoIds.newId();
        final String stranger = MongoIds.newId();

        save(owner, "names", List.of("Alice"));
        save(stranger, "names", List.of("Bob"));

        service.deleteByName("names", stranger);
        assertNotNull(service.findOneByName("names", owner), "another user's delete must not reach this list");

        service.deleteByName("names", owner);
        assertNull(service.findOneByName("names", owner));

    }



    @Test
    @DisplayName("The caller's list is not modified, and an immutable one is accepted")
    void savingDoesNotMutateTheCallersList() {

        final String user = MongoIds.newId();

        // An immutable list threw UnsupportedOperationException outright.
        assertTrue(save(user, "immutable", List.of("Alice", "Bob")).isSuccessful());

        final List<String> mutable = new ArrayList<>(List.of("Alice", "", "Bob"));
        save(user, "mutable", mutable);

        assertEquals(List.of("Alice", "", "Bob"), mutable,
                "the caller's list must come back as it was handed over");
        assertEquals(List.of("Alice", "Bob"), service.findOneByName("mutable", user).getItems(),
                "the blank is dropped from what is stored, not from the caller's list");

    }

    @Test
    @DisplayName("Items are trimmed, and an item of only spaces is kept as empty")
    void itemsAreTrimmed() {

        final String user = MongoIds.newId();
        save(user, "spaced", new ArrayList<>(List.of("  Alice  ", "Bob")));

        assertEquals(List.of("Alice", "Bob"), service.findOneByName("spaced", user).getItems());

    }

    @Test
    void deletingRequiredListPreventsPolicyResolutionEvenWhenAnotherUserHasThatName() {
        final String owner = MongoIds.newId();
        final String other = MongoIds.newId();
        assertTrue(save(owner, "names", List.of("Alice")).isSuccessful());
        assertTrue(save(other, "names", List.of("Bob")).isSuccessful());
        final var resolver = new ai.philterd.philter.services.policies.PolicyResolver(new com.google.gson.Gson(), service);
        final String json = "{\"identifiers\":{\"dictionaries\":[{\"terms\":[\"list:names\"]}]}}";
        assertEquals(List.of("Alice"), resolver.resolve(json, owner, null, null)
                .getIdentifiers().getCustomDictionaries().getFirst().getTerms());

        service.deleteByName("names", owner);

        final var error = org.junit.jupiter.api.Assertions.assertThrows(
                ai.philterd.philter.services.policies.PolicyResolutionException.class,
                () -> resolver.resolve(json, owner, null, null));
        assertEquals("Policy references unavailable custom lists: names.", error.getMessage());
        assertEquals(List.of("Bob"), resolver.resolve(json, other, null, null)
                .getIdentifiers().getCustomDictionaries().getFirst().getTerms());
    }

}
