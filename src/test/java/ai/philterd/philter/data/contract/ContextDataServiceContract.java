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
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.model.ServiceResponse;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link ContextDataService}. */
public interface ContextDataServiceContract extends StorageContract {

    @Test
    default void contextNamesAreUniquePerUser() throws Exception {
        final String owner = RecordIds.newId();
        final List<ServiceResponse> created = StorageContract.concurrently(5,
                () -> storage().contexts().create("claims", owner));
        assertEquals(1, created.stream().filter(ServiceResponse::isSuccessful).count());
        assertEquals(1, storage().contexts().count(owner));
        assertTrue(storage().contexts().create("claims", RecordIds.newId()).isSuccessful());
    }

    @Test
    default void contextsNeverExceedTheLimitEvenWhenCreatedConcurrently() throws Exception {
        final String owner = RecordIds.newId();
        final int attempts = ContextDataService.MAXIMUM_CONTEXTS_PER_USER + 6;
        final AtomicInteger next = new AtomicInteger();
        final List<ServiceResponse> created = StorageContract.concurrently(attempts,
                () -> storage().contexts().create("context-" + next.incrementAndGet(), owner));
        assertEquals(ContextDataService.MAXIMUM_CONTEXTS_PER_USER, created.stream().filter(ServiceResponse::isSuccessful).count());
        assertEquals(ContextDataService.MAXIMUM_CONTEXTS_PER_USER, storage().contexts().count(owner));
        final ServiceResponse over = storage().contexts().create("one-more", owner);
        assertFalse(over.isSuccessful());
        assertEquals(ContextDataService.REASON_CONTEXT_LIMIT_REACHED, over.getDetails());
    }

    @Test
    default void contextSettingsReadBackUnchanged() {
        final String owner = RecordIds.newId();
        assertTrue(storage().contexts().create("claims", owner, true, "Context", true).isSuccessful());
        final ContextEntity context = storage().contexts().findOneByNameAndUserId("claims", owner);
        assertTrue(context.isDisambiguation());
        assertTrue(context.isLedger());
        assertEquals("Context", context.getDisambiguationScope());
    }

    @Test
    default void contextListingsCountPageSortAndSearch() {
        final String owner = RecordIds.newId();
        for (final String name : List.of("alpha", "Beta", "gamma", "delta", "epsilon")) {
            assertTrue(storage().contexts().create(name, owner).isSuccessful());
        }
        final Listings.Sort byName = new Listings.Sort("context_name", false);
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            final Listings.Page<ContextEntity> page = storage().contexts().list(owner, null, byName, offset, 2);
            assertEquals(5, page.total());
            page.items().forEach(context -> assertTrue(seen.add(context.getContextName())));
        }
        assertEquals(List.of("gamma", "epsilon"), storage().contexts().list(owner, null, new Listings.Sort("context_name", true), 0, 2)
                .items().stream().map(ContextEntity::getContextName).toList());
        assertEquals(List.of("Beta"), storage().contexts().list(owner, "bET", byName, 0, 10)
                .items().stream().map(ContextEntity::getContextName).toList());
    }

}
