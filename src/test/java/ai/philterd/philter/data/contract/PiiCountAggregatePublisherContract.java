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

import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link PiiCountAggregatePublisher}. */
public interface PiiCountAggregatePublisherContract extends StorageContract {

    private void enable(final boolean enabled) {
        assertTrue(storage().users().createUser("req", "admin", null, UserService.ROLE_ADMIN, storage().policies(),
                storage().contexts(), "test").isSuccessful());
        storage().adminSettings().update(new AdminSettingsDataService.Update(enabled, null, null, null, null, null, null,
                null, null, null), storage().users().findByUsername("admin").getId(), null);
    }

    @Test
    default void piiCountConcurrentRedactionsAreAllCounted() throws Exception {
        enable(true);
        StorageContract.concurrently(10, () -> {
            storage().piiCounts().record("claims", Set.of("PERSON", "SSN"));
            return null;
        });
        storage().piiCounts().record("claims", Set.of("PERSON"));
        final Map<String, Long> counts = storage().piiCountsToday("claims");
        assertEquals(11L, counts.get("total_documents"));
        assertEquals(11L, counts.get("PERSON"));
        assertEquals(10L, counts.get("SSN"));
        assertTrue(storage().piiCountsToday("another-context").isEmpty());
    }

    @Test
    default void piiCountRecordingIsANoOpWhenDisabledAndNeverThrows() {
        enable(false);
        storage().piiCounts().record("claims", Set.of("PERSON"));
        assertTrue(storage().piiCountsToday("claims").isEmpty());
        assertDoesNotThrow(() -> storage().piiCounts().record(null, null));
        assertDoesNotThrow(() -> storage().piiCounts().record("claims", Set.of()));
    }

}
