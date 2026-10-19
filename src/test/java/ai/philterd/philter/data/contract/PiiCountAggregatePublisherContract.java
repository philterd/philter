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
import ai.philterd.philter.data.services.PiiCountDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link PiiCountAggregatePublisher}. */
public interface PiiCountAggregatePublisherContract extends StorageContract {

    /** The user the counts are recorded for. */
    String OWNER = "6a0f1c2e9b1d4e3f2a1b0c9d";

    private void enable(final boolean enabled) {
        assertTrue(storage().users().createUser("req", "admin", null, UserService.ROLE_ADMIN, storage().policies(),
                storage().contexts(), "test").isSuccessful());
        storage().adminSettings().update(new AdminSettingsDataService.Update(enabled, null, null, null, null, null, null,
                null, null, null), storage().users().findByUsername("admin").getId(), null);
    }

    /** The counts recorded for a context today, by type, with {@code total_documents} for the redactions. */
    private Map<String, Long> today(final String context) {
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        final List<PiiCountDataService.Aggregate> aggregates =
                storage().piiCountData().list(today, today, OWNER, context, 0, 10).items();
        final Map<String, Long> counts = new HashMap<>();
        if (!aggregates.isEmpty()) {
            assertEquals(1, aggregates.size());
            counts.putAll(aggregates.get(0).counts());
            counts.put("total_documents", aggregates.get(0).totalDocuments());
        }
        return counts;
    }

    @Test
    default void piiCountConcurrentRedactionsAreAllCounted() throws Exception {
        enable(true);
        StorageContract.concurrently(10, () -> {
            storage().piiCounts().record(OWNER, "claims", Set.of("PERSON", "SSN"));
            return null;
        });
        storage().piiCounts().record(OWNER, "claims", Set.of("PERSON"));
        final Map<String, Long> counts = today("claims");
        assertEquals(11L, counts.get("total_documents"));
        assertEquals(11L, counts.get("PERSON"));
        assertEquals(10L, counts.get("SSN"));
        assertTrue(today("another-context").isEmpty());
    }

    @Test
    default void piiCountUsersAreCountedApart() {
        enable(true);
        storage().piiCounts().record(OWNER, "default", Set.of("SSN"));
        storage().piiCounts().record("6a0f1c2e9b1d4e3f2a1b0c9e", "default", Set.of("SSN"));
        assertEquals(1L, today("default").get("SSN"), "another user's context of the same name is counted apart");
    }

    @Test
    default void piiCountRecordingIsANoOpWhenDisabledAndNeverThrows() {
        enable(false);
        storage().piiCounts().record(OWNER, "claims", Set.of("PERSON"));
        assertTrue(today("claims").isEmpty());
        assertDoesNotThrow(() -> storage().piiCounts().record(null, null, null));
        assertDoesNotThrow(() -> storage().piiCounts().record(OWNER, "claims", Set.of()));
    }

    @Test
    default void piiCountRecordingFollowsTheSettingAtOnce() {
        enable(true);
        storage().piiCounts().record(OWNER, "toggled", Set.of("SSN"));
        // Turned off and on again straight away: each change applies to the next redaction.
        setRecording(false);
        storage().piiCounts().record(OWNER, "toggled", Set.of("SSN"));
        assertEquals(1L, today("toggled").get("SSN"), "a redaction was counted after recording was turned off");
        setRecording(true);
        storage().piiCounts().record(OWNER, "toggled", Set.of("SSN"));
        assertEquals(2L, today("toggled").get("SSN"), "a redaction was not counted after recording was turned back on");
    }

    /** Changes the recording setting, once {@link #enable} has created the administrator. */
    private void setRecording(final boolean enabled) {
        storage().adminSettings().update(new AdminSettingsDataService.Update(enabled, null, null, null, null, null, null,
                null, null, null), storage().users().findByUsername("admin").getId(), null);
    }

}
