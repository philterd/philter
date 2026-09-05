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
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * A context exists so one person is replaced by one pseudonym everywhere. Recording that mapping used
 * to be a read followed by an insert, so two documents redacted at the same time both found nothing,
 * both inserted, and each went on using the replacement it had invented.
 */
class ContextEntryConcurrencyIT extends AbstractMongoIT {

    private ContextEntryDataService service;

    @BeforeEach
    void setUpService() {
        service = new ContextEntryDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    @Test
    @DisplayName("Two writers of the same token agree on one replacement, every time")
    void concurrentWritersAgreeOnOneReplacement() throws Exception {

        final List<String> disagreements = new ArrayList<>();
        final List<String> duplicated = new ArrayList<>();

        for (int round = 0; round < 40; round++) {

            final ObjectId user = new ObjectId();
            final CountDownLatch go = new CountDownLatch(1);
            final List<String> returned = Collections.synchronizedList(new ArrayList<>());

            final Thread a = writer(go, returned, user, "John Smith", "David Jones");
            final Thread b = writer(go, returned, user, "John Smith", "Alice Brown");

            a.start();
            b.start();
            go.countDown();
            a.join(20_000);
            b.join(20_000);

            if (service.countByUserIdAndContext(user, "ctx") != 1) {
                duplicated.add("round " + round);
            }
            if (Set.copyOf(returned).size() != 1) {
                disagreements.add("round " + round + " " + returned);
            }

        }

        assertEquals(List.of(), duplicated, "one token must be stored once");
        assertEquals(List.of(), disagreements,
                "both writers must be told the same replacement, or their documents disagree");

    }

    @Test
    @DisplayName("The winner's replacement is the one stored, and the loser is told it")
    void theLoserIsToldTheStoredValue() {

        final ObjectId user = new ObjectId();

        final ContextEntryEntity first = service.putReplacementIfAbsent(user, "ctx", "John Smith", "David Jones", "PERSON");
        final ContextEntryEntity second = service.putReplacementIfAbsent(user, "ctx", "John Smith", "Alice Brown", "PERSON");

        assertEquals("David Jones", first.getReplacement());
        assertEquals("David Jones", second.getReplacement(), "the second writer must be told what is stored");
        assertEquals("David Jones", service.getReplacement(user, "ctx", "John Smith"));
        assertEquals(1, service.countByUserIdAndContext(user, "ctx"));

    }

    @Test
    @DisplayName("A second write does not disturb the entry that is already there")
    void asecondWriteLeavesTheStoredEntryAlone() {

        final ObjectId user = new ObjectId();

        final ContextEntryEntity first = service.putReplacementIfAbsent(user, "ctx", "John Smith", "David Jones", "PERSON");
        service.incrementReads(first.getId());

        final ContextEntryEntity second = service.putReplacementIfAbsent(user, "ctx", "John Smith", "Alice Brown", "OTHER");

        assertEquals(first.getId(), second.getId(), "the same row");
        assertEquals("PERSON", second.getFilterType(), "the filter type must not be overwritten");
        assertEquals(1L, second.getReads(), "the read count must survive a second write");

    }

    @Test
    @DisplayName("The unique index exists, and is unique")
    void theTokenIndexIsUnique() {

        final List<Document> indexes = new ArrayList<>();
        mongoClient.getDatabase("philter").getCollection("context_entries").listIndexes().into(indexes);

        final Document tokenIndex = indexes.stream()
                .filter(i -> ((Document) i.get("key")).containsKey("token_hash"))
                .findFirst()
                .orElse(null);

        assertNotNull(tokenIndex, "the token lookup index must exist");
        assertTrue(tokenIndex.getBoolean("unique", false),
                "it must be unique, or a concurrent insert is stored rather than refused: " + tokenIndex);

    }

    @Test
    @DisplayName("Different tokens, contexts and users stay separate")
    void uniquenessIsScopedToOneTokenInOneContextForOneUser() {

        final ObjectId user = new ObjectId();
        final ObjectId other = new ObjectId();

        service.putReplacementIfAbsent(user, "ctx", "John Smith", "David Jones", "PERSON");
        service.putReplacementIfAbsent(user, "ctx", "Jane Doe", "Mary Poe", "PERSON");
        service.putReplacementIfAbsent(user, "other-ctx", "John Smith", "Someone Else", "PERSON");
        service.putReplacementIfAbsent(other, "ctx", "John Smith", "Third Person", "PERSON");

        assertEquals(2, service.countByUserIdAndContext(user, "ctx"));
        assertEquals(1, service.countByUserIdAndContext(user, "other-ctx"));
        assertEquals(1, service.countByUserIdAndContext(other, "ctx"));

        assertEquals("David Jones", service.getReplacement(user, "ctx", "John Smith"));
        assertEquals("Someone Else", service.getReplacement(user, "other-ctx", "John Smith"));
        assertEquals("Third Person", service.getReplacement(other, "ctx", "John Smith"));

    }

    @Test
    @DisplayName("Many writers of many tokens store exactly one row each")
    void manyConcurrentWritersOfManyTokens() throws Exception {

        final ObjectId user = new ObjectId();
        final int tokens = 30;
        final int writersPerToken = 4;
        final CountDownLatch go = new CountDownLatch(1);
        final List<Thread> threads = new ArrayList<>();
        final List<String> returned = Collections.synchronizedList(new ArrayList<>());

        for (int t = 0; t < tokens; t++) {
            for (int w = 0; w < writersPerToken; w++) {
                final String token = "token-" + t;
                final String proposal = "replacement-" + t + "-" + w;
                threads.add(writer(go, returned, user, token, proposal));
            }
        }

        threads.forEach(Thread::start);
        go.countDown();
        for (final Thread thread : threads) {
            thread.join(30_000);
        }

        assertEquals(tokens, service.countByUserIdAndContext(user, "ctx"),
                "one row per token, however many writers raced for it");

        // Every writer of a token was told the same thing, so distinct answers equals distinct tokens.
        assertEquals(tokens, Set.copyOf(returned).size(),
                "each token must have exactly one replacement across all its writers");

    }

    @Test
    @DisplayName("A token is still readable and counted after the race")
    void theStoredEntryIsUsableAfterwards() throws Exception {

        final ObjectId user = new ObjectId();
        final CountDownLatch go = new CountDownLatch(1);
        final List<String> returned = Collections.synchronizedList(new ArrayList<>());

        final Thread a = writer(go, returned, user, "John Smith", "David Jones");
        final Thread b = writer(go, returned, user, "John Smith", "Alice Brown");
        a.start();
        b.start();
        go.countDown();
        a.join(20_000);
        b.join(20_000);

        final String agreed = returned.get(0);

        assertTrue(service.containsToken(user, "ctx", "John Smith"));
        assertEquals(agreed, service.getReplacement(user, "ctx", "John Smith"));

        final ContextEntryEntity entry = service.findOneEntryByToken(user, "ctx", "John Smith");
        assertNotNull(entry);
        assertEquals(agreed, entry.getReplacement());
        assertEquals(1, service.findAllByUserIdAndContext(user, "ctx").size());

    }

    private Thread writer(final CountDownLatch go, final List<String> returned, final ObjectId user,
                          final String token, final String proposal) {
        return new Thread(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            final ContextEntryEntity stored = service.putReplacementIfAbsent(user, "ctx", token, proposal, "PERSON");
            returned.add(stored == null ? "<null>" : stored.getReplacement());
        });
    }


    @Test
    @DisplayName("A repeat write does not evict anything, because it stored nothing")
    void aRepeatWriteEvictsNothing() {

        final ObjectId user = new ObjectId();

        for (int i = 0; i < 10; i++) {
            service.putReplacementIfAbsent(user, "ctx", "token-" + i, "replacement-" + i, "PERSON");
        }
        assertEquals(10, service.countByUserIdAndContext(user, "ctx"));

        // Eviction used to run before the insert, so a write that changes nothing would still have
        // made room for it.
        for (int i = 0; i < 10; i++) {
            service.putReplacementIfAbsent(user, "ctx", "token-" + i, "another-replacement", "PERSON");
        }

        assertEquals(10, service.countByUserIdAndContext(user, "ctx"), "nothing was stored, so nothing may be evicted");
        assertEquals("replacement-0", service.getReplacement(user, "ctx", "token-0"));
        assertEquals("replacement-9", service.getReplacement(user, "ctx", "token-9"));

    }

}
