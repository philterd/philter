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

import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PiiCountDataService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The guarantees of {@link PiiCountDataService}. */
public interface PiiCountDataServiceContract extends StorageContract {

    LocalDate FIRST = LocalDate.parse("2026-09-01");

    /** The users the counts belong to. */
    String OWNER = "6a0f1c2e9b1d4e3f2a1b0c9d";
    String OTHER = "6a0f1c2e9b1d4e3f2a1b0c9e";

    /** Three days of counts for two contexts. */
    private void storeThreeDays() {
        for (int day = 0; day < 3; day++) {
            storage().storePiiCounts(OWNER, "billing", FIRST.plusDays(day), Map.of("SSN", 10L + day), 20 + day);
            storage().storePiiCounts(OWNER, "claims", FIRST.plusDays(day), Map.of("SSN", 1L + day, "PERSON", 5L), 9);
        }
    }

    private static List<String> keys(final List<PiiCountDataService.Aggregate> aggregates) {
        return aggregates.stream().map(aggregate -> aggregate.day() + " " + aggregate.context()).toList();
    }

    @Test
    default void piiCountDataKeepsEachUsersContextsApart() {
        storage().storePiiCounts(OWNER, "default", FIRST, Map.of("SSN", 3L), 3);
        storage().storePiiCounts(OTHER, "default", FIRST, Map.of("SSN", 5L), 5);
        final List<PiiCountDataService.Aggregate> both = storage().piiCountData().list(null, null, null, "default", 0, 10).items();
        assertEquals(2, both.size(), "two users' contexts of one name are not counted together");
        final List<PiiCountDataService.Aggregate> mine = storage().piiCountData().list(null, null, OWNER, "default", 0, 10).items();
        assertEquals(1, mine.size());
        assertEquals(OWNER, mine.get(0).userId());
        assertEquals(Map.of("SSN", 3L), mine.get(0).counts());
    }

    @Test
    default void piiCountDataReadsBackTheRecordedCounts() {
        storage().storePiiCounts(OWNER, "claims", FIRST, Map.of("SSN", 1240L, "EMAIL_ADDRESS", 8900L), 10_000);
        final List<PiiCountDataService.Aggregate> aggregates =
                storage().anotherInstance().piiCountData().list(null, null, null, null, 0, 10).items();
        assertEquals(1, aggregates.size());
        assertEquals("claims", aggregates.get(0).context());
        assertEquals(FIRST, aggregates.get(0).day());
        assertEquals(Map.of("SSN", 1240L, "EMAIL_ADDRESS", 8900L), aggregates.get(0).counts());
        assertEquals(10_000, aggregates.get(0).totalDocuments());
    }

    @Test
    default void piiCountDataListsMostRecentDayFirstThenByContextAndPagesWithoutRepeats() {
        storeThreeDays();
        final List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 6; offset += 4) {
            final Listings.Page<PiiCountDataService.Aggregate> page = storage().piiCountData().list(null, null, null, null, offset, 4);
            assertEquals(6, page.total());
            paged.addAll(keys(page.items()));
        }
        assertEquals(List.of("2026-09-03 billing", "2026-09-03 claims", "2026-09-02 billing", "2026-09-02 claims",
                "2026-09-01 billing", "2026-09-01 claims"), paged);
    }

    @Test
    default void piiCountDataSortsByContextAndByDocumentsThenByDay() {
        storeThreeDays();
        assertEquals(List.of("2026-09-03 claims", "2026-09-02 claims", "2026-09-01 claims",
                        "2026-09-03 billing", "2026-09-02 billing", "2026-09-01 billing"),
                keys(storage().piiCountData().list(null, null, null, null, new Listings.Sort("context", true), 0, 10).items()));
        // billing found PII in 20, 21, and 22 redactions; claims in 9 each day, so those tie and fall back to the day.
        assertEquals(List.of("2026-09-03 claims", "2026-09-02 claims", "2026-09-01 claims",
                        "2026-09-01 billing", "2026-09-02 billing", "2026-09-03 billing"),
                keys(storage().piiCountData().list(null, null, null, null, new Listings.Sort("total_documents", false), 0, 10).items()));
        assertEquals(List.of("2026-09-01 billing", "2026-09-01 claims"),
                keys(storage().piiCountData().list(null, null, null, null, new Listings.Sort("bucket_start", false), 0, 2).items()));
    }

    @Test
    default void piiCountDataNarrowsByDaysAndContext() {
        storeThreeDays();
        final Listings.Page<PiiCountDataService.Aggregate> middle =
                storage().piiCountData().list(FIRST.plusDays(1), FIRST.plusDays(1), null, null, 0, 10);
        assertEquals(List.of("2026-09-02 billing", "2026-09-02 claims"), keys(middle.items()));
        assertEquals(2, middle.total());
        final Listings.Page<PiiCountDataService.Aggregate> claimsFrom =
                storage().piiCountData().list(FIRST.plusDays(1), null, null, "claims", 0, 10);
        assertEquals(List.of("2026-09-03 claims", "2026-09-02 claims"), keys(claimsFrom.items()));
        assertEquals(2, claimsFrom.total());
        assertEquals(List.of("2026-09-01 billing"),
                keys(storage().piiCountData().list(null, FIRST, null, "billing", 0, 10).items()));
        assertEquals(0, storage().piiCountData().list(null, null, null, "other", 0, 10).total());
    }

}
