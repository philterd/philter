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
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link ContextEntryDataService}. */
public interface ContextEntryDataServiceContract extends StorageContract {

    @Test
    default void contextEntryConcurrentRedactionsOfOneTokenShareOneReplacement() throws Exception {
        final String owner = newOwner();
        final AtomicInteger next = new AtomicInteger();
        final List<String> replacements = StorageContract.concurrently(8, () -> storage().anotherInstance().contextEntries()
                .putReplacementIfAbsent(owner, "default", "Jordan Smith", "replacement-" + next.incrementAndGet(), "PERSON")
                .getReplacement());
        assertEquals(1, Set.copyOf(replacements).size());
        assertEquals(1, storage().contextEntries().countByUserIdAndContext(owner, "default"));
        assertEquals(replacements.get(0), storage().contextEntries().getReplacement(owner, "default", "Jordan Smith"));
    }

    @Test
    default void contextEntryConcurrentReadsAreAllCounted() throws Exception {
        final String owner = newOwner();
        final ContextEntryEntity entry = storage().contextEntries()
                .putReplacementIfAbsent(owner, "default", "Jordan Smith", "replacement", "PERSON");
        final long before = storage().contextEntries().findOneEntryByToken(owner, "default", "Jordan Smith").getReads();
        StorageContract.concurrently(10, () -> {
            storage().contextEntries().incrementReads(entry.getId());
            return null;
        });
        assertEquals(before + 10, storage().contextEntries().findOneEntryByToken(owner, "default", "Jordan Smith").getReads());
    }

    @Test
    default void contextEntryListingsCountAndPageWithoutRepeats() {
        final String owner = newOwner();
        for (int i = 0; i < 5; i++) {
            storage().contextEntries().putReplacementIfAbsent(owner, "default", "token-" + i, "replacement-" + i, "PERSON");
        }
        storage().contextEntries().putReplacementIfAbsent(owner, "other", "token", "replacement", "PERSON");
        // Another owner's context of the same name is never listed or counted.
        final String other = newOwner();
        for (int i = 0; i < 3; i++) {
            storage().contextEntries().putReplacementIfAbsent(other, "default", "token-" + i, "theirs-" + i, "PERSON");
        }
        assertEquals(5, storage().contextEntries().countByUserIdAndContext(owner, "default"));
        final Set<String> seen = new HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().contextEntries().findAllByUserIdAndContext(owner, "default", offset, 2)
                    .forEach(entry -> {
                        assertTrue(seen.add(entry.getId()));
                        assertEquals(owner, entry.getUserId());
                    });
        }
        assertEquals(5, seen.size());
    }

    @Test
    default void contextEntryAFullContextEvictsItsLeastReadEntry() {
        final String owner = newOwner();
        final ContextEntryDataService entries = storage().contextEntriesHolding(3);
        final ContextEntryEntity first = entries.putReplacementIfAbsent(owner, "default", "first", "replacement-1", "PERSON");
        entries.putReplacementIfAbsent(owner, "default", "second", "replacement-2", "PERSON");
        entries.incrementReads(first.getId());

        // A put that stores nothing new evicts nothing.
        entries.putReplacementIfAbsent(owner, "default", "first", "other", "PERSON");
        assertEquals(2, entries.countByUserIdAndContext(owner, "default"));

        final ContextEntryEntity third = entries.putReplacementIfAbsent(owner, "default", "third", "replacement-3", "PERSON");
        entries.incrementReads(third.getId());
        assertEquals(3, entries.countByUserIdAndContext(owner, "default"), "a context fills to its capacity");

        final ContextEntryEntity fourth = entries.putReplacementIfAbsent(owner, "default", "fourth", "replacement-4", "PERSON");
        assertEquals(3, entries.countByUserIdAndContext(owner, "default"), "and stays at it");
        assertNull(entries.findOneEntryByToken(owner, "default", "second"), "the entry never read is evicted");
        assertNotNull(entries.findOneEntryByToken(owner, "default", "first"));
        assertNotNull(entries.findOneEntryByToken(owner, "default", "third"));
        assertNotNull(entries.findOneEntryByToken(owner, "default", "fourth"), "the new entry is kept");

        // Every entry has been read, so the new entry has the fewest reads; it is still the one kept.
        entries.incrementReads(fourth.getId());
        for (int i = 0; i < 5; i++) {
            final ContextEntryEntity added = entries.putReplacementIfAbsent(owner, "default", "more-" + i, "replacement-" + i,
                    "PERSON");
            assertEquals("replacement-" + i, added.getReplacement());
            assertEquals(3, entries.countByUserIdAndContext(owner, "default"), "the context holds exactly its capacity");
            assertEquals("replacement-" + i, entries.findOneEntryByToken(owner, "default", "more-" + i).getReplacement(),
                    "the replacement a put returns is the one kept");
            entries.incrementReads(added.getId());
        }

        // Another owner's context is not counted or evicted from.
        final String other = newOwner();
        entries.putReplacementIfAbsent(other, "default", "first", "replacement", "PERSON");
        assertEquals(3, entries.countByUserIdAndContext(owner, "default"));
        assertEquals(1, entries.countByUserIdAndContext(other, "default"));
    }

}
