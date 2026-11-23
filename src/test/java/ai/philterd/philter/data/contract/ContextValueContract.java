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

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.services.context.StoredContextService;
import ai.philterd.philter.services.vectors.NoOpVectorService;
import com.google.gson.Gson;
import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** The guarantees for a context's values, such as Phileas's random date shift, kept with the context. */
public interface ContextValueContract extends StorageContract {

    /** The key Phileas keeps a context's random date shift under. */
    String SHIFT_KEY = "\0date-shift";

    /** {@link #SHIFT_KEY} as stored. */
    String STORED_SHIFT_KEY = "%00date-shift";

    private StoredContextService contextService(final StorageUnderTest storage, final ContextEntryDataService entries,
                                                final String owner) {
        return new StoredContextService(new ContextCache("", 0, "", false), entries, storage.contexts(), owner, "default");
    }

    @Test
    default void contextValueKeysArePercentEncoded() {
        assertEquals(STORED_SHIFT_KEY, ContextDataService.valueKey(SHIFT_KEY));
        assertEquals("a%2Eb%24c%25", ContextDataService.valueKey("a.b$c%"));
        assertTrue(ContextDataService.VALUE_KEY.matcher(ContextDataService.valueKey("été shift")).matches());
    }

    @Test
    default void contextValueConcurrentFirstUseKeepsOneValue() throws Exception {
        final String owner = newOwner();
        final AtomicInteger next = new AtomicInteger();
        // Each caller is a separate request, through another instance, as two documents filtered at once are.
        final List<String> shifts = StorageContract.concurrently(8, () -> {
            final StorageUnderTest other = storage().anotherInstance();
            return contextService(other, other.contextEntries(), owner)
                    .computeValueIfAbsent(SHIFT_KEY, "date", () -> String.valueOf(next.incrementAndGet()));
        });
        assertEquals(1, Set.copyOf(shifts).size(), "every request uses the shift that was kept");
        assertEquals(shifts.get(0), storage().contexts().findOne("default", owner).getValues().get(STORED_SHIFT_KEY));
    }

    @Test
    default void contextValueIsNeverEvictedListedOrCounted() {
        final String owner = newOwner();
        final ContextEntryDataService entries = storage().contextEntriesHolding(2);
        final StoredContextService service = contextService(storage(), entries, owner);

        assertEquals("-400", service.computeValueIfAbsent(SHIFT_KEY, "date", () -> "-400"));
        for (int i = 0; i < 5; i++) {
            final String replacement = "replacement-" + i;
            service.computeReplacementIfAbsent("token-" + i, "PERSON", () -> replacement);
        }

        assertEquals(2, entries.countByUserIdAndContext(owner, "default"), "the shift does not count toward capacity");
        for (final ContextEntryEntity entry : entries.findAllByUserIdAndContext(owner, "default")) {
            assertNotEquals("-400", entry.getReplacement(), "the shift is not an entry");
        }
        assertEquals("-400", contextService(storage(), entries, owner)
                .computeValueIfAbsent(SHIFT_KEY, "date", () -> "a new shift"), "the shift is not evicted");
    }

    @Test
    default void contextValueIsKeptWhenEntriesAreClearedAndRemovedWithTheContext() {
        final String owner = newOwner();
        assertEquals("-400", storage().contexts().putValueIfAbsent(owner, "default", STORED_SHIFT_KEY, "-400"));
        storage().contextEntries().putReplacementIfAbsent(owner, "default", "token", "replacement", "PERSON");

        assertTrue(storage().contexts().emptyByName("default", owner).isSuccessful());
        assertEquals(0, storage().contextEntries().countByUserIdAndContext(owner, "default"));
        assertEquals("-400", storage().contexts().findOne("default", owner).getValues().get(STORED_SHIFT_KEY),
                "clearing the entries keeps the shift");

        assertTrue(storage().contexts().deleteByName("default", owner).isSuccessful());
        assertTrue(storage().contexts().create("default", owner).isSuccessful());
        assertTrue(storage().contexts().findOne("default", owner).getValues().isEmpty(),
                "deleting the context removes its shift");
    }

    @Test
    default void contextValuePutRules() {
        final String owner = newOwner();
        final String other = newOwner();

        assertEquals("-400", storage().contexts().putValueIfAbsent(owner, "default", STORED_SHIFT_KEY, "-400"));
        assertEquals("-400", storage().contexts().putValueIfAbsent(owner, "default", STORED_SHIFT_KEY, "-12"),
                "the first value wins");
        assertTrue(storage().contexts().putValue(owner, "default", STORED_SHIFT_KEY, "-12"));
        assertEquals("-12", storage().contexts().findOne("default", owner).getValues().get(STORED_SHIFT_KEY),
                "putValue replaces it");
        assertTrue(storage().contexts().findOne("default", other).getValues().isEmpty(), "only that context's");

        assertNull(storage().contexts().putValueIfAbsent(owner, "missing", STORED_SHIFT_KEY, "-400"));
        assertFalse(storage().contexts().putValue(owner, "missing", STORED_SHIFT_KEY, "-400"));

        assertThrows(IllegalArgumentException.class,
                () -> storage().contexts().putValueIfAbsent(owner, "default", SHIFT_KEY, "-400"));
        assertThrows(IllegalArgumentException.class,
                () -> storage().contexts().putValue(owner, "default", STORED_SHIFT_KEY, ""));
    }

    @Test
    default void contextValueKeepsARandomDateShiftAcrossDocuments() throws Exception {
        final String owner = newOwner();
        final ContextEntryDataService entries = storage().contextEntriesHolding(1);
        final Policy policy = new Gson().fromJson("{\"identifiers\":{"
                + "\"date\":{\"dateFilterStrategies\":[{\"strategy\":\"SHIFT\",\"shiftRandom\":true,\"replacementScope\":\"CONTEXT\"}]},"
                + "\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"RANDOM_REPLACE\",\"replacementScope\":\"CONTEXT\"}]}}}",
                Policy.class);

        // Each document is its own request, with a new context service, so only storage carries the shift.
        final long shift = shift(redact(storage(), entries, owner, policy, "Admitted 03/27/2020, SSN 123-45-6789."),
                "03/27/2020");
        assertTrue(Math.abs(shift) >= 30, "a random shift moves a date by at least 30 days");

        // The second document's SSN fills the one-entry context, evicting the first's.
        assertEquals(shift, shift(redact(storage(), entries, owner, policy, "Discharged 11/02/2021, SSN 987-65-4320."),
                "11/02/2021"));
        assertEquals(1, entries.countByUserIdAndContext(owner, "default"));

        assertTrue(storage().contexts().emptyByName("default", owner).isSuccessful());
        assertEquals(shift, shift(redact(storage(), entries, owner, policy, "Seen again 01/15/2022."), "01/15/2022"),
                "clearing the entries keeps the shift");
    }

    private TextFilterResult redact(final StorageUnderTest storage, final ContextEntryDataService entries, final String owner,
                                    final Policy policy, final String text) throws Exception {
        final Properties properties = new Properties();
        properties.put("span.disambiguation.enabled", "false");
        return new PlainTextFilterService(new PhileasConfiguration(properties), contextService(storage, entries, owner),
                new NoOpVectorService(), mock(HttpClient.class)).filter(policy, "default", text);
    }

    /** The days between {@code original} and the date that replaced it. */
    private static long shift(final TextFilterResult result, final String original) {
        final Matcher matcher = Pattern.compile("\\d{2}/\\d{2}/\\d{4}").matcher(result.getFilteredText());
        assertTrue(matcher.find(), result.getFilteredText());
        final DateTimeFormatter format = DateTimeFormatter.ofPattern("MM/dd/yyyy");
        return ChronoUnit.DAYS.between(LocalDate.parse(original, format), LocalDate.parse(matcher.group(), format));
    }

}
