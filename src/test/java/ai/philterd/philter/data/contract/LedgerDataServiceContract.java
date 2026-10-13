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

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.StaleLedgerEntryException;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link LedgerDataService}. */
public interface LedgerDataServiceContract extends StorageContract {

    private void chain(final String owner, final String documentId, final List<String> tokens) throws Exception {
        final LedgerDataService ledger = storage().ledger();
        ledger.initializeLedger(owner, documentId, "input-hash", "file.pdf", "policy", 0, "policy-hash");
        for (final String token : tokens) {
            ledger.addTransaction(entry(owner, documentId, token));
        }
        ledger.completeChain(owner, documentId);
    }

    private LedgerEntity entry(final String owner, final String documentId, final String token) throws Exception {
        return new LedgerEntity(owner, documentId, token, "{{{REDACTED-" + token + "}}}", 0, "input-hash",
                storage().ledger().getLatestTransaction(owner, documentId).getHash(), "file.pdf", "ssn", "policy", 0,
                "policy-hash");
    }

    @Test
    default void ledgerAppendsKeepTheHashChainAndASealedChainTakesNoMore() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789", "987-65-4321"));
        assertTrue(storage().ledger().validateChain(owner, "doc").hashChainValid());
        final LedgerEntity late = entry(owner, "doc", "111-22-3333");
        assertThrows(IllegalStateException.class, () -> storage().ledger().addTransaction(late));
        assertTrue(storage().anotherInstance().ledger().validateChain(owner, "doc").hashChainValid());
    }

    @Test
    default void ledgerTokensAndReplacementsReadBackUnchanged() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("Ünïcödé, \"quoted\""));
        final LedgerEntity stored = storage().anotherInstance().ledger().getChain(owner, "doc").stream()
                .filter(entry -> "Ünïcödé, \"quoted\"".equals(entry.getToken())).findFirst().orElseThrow();
        assertEquals("{{{REDACTED-Ünïcödé, \"quoted\"}}}", stored.getReplacement());
    }

    @Test
    default void ledgerEntriesHeldByALegalHoldAreNotDeleted() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789"));
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc",
                "litigation", owner, owner).getStatusCode());
        assertFalse(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        assertTrue(storage().ledger().chainExists(owner, "doc"));
        assertTrue(storage().legalHolds().release("req", "case-1", owner).isSuccessful());
        assertTrue(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        assertFalse(storage().ledger().chainExists(owner, "doc"));
    }

    @Test
    default void ledgerListingsCountAndPageChains() throws Exception {
        final String owner = RecordIds.newId();
        for (int i = 0; i < 5; i++) {
            chain(owner, "doc-" + i, List.of("123-45-6789"));
        }
        assertEquals(5, storage().ledger().listChains("req", owner, null, new ai.philterd.philter.data.services.Listings.Sort("timestamp", true), 0, 1, "test").total());
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().ledger().listChains("req", owner, null, new ai.philterd.philter.data.services.Listings.Sort("timestamp", true), offset, 2, "test").items()
                    .forEach(head -> assertTrue(seen.add(head.getDocumentId())));
        }
        assertEquals(5, seen.size());
    }

    private List<AuditLogService.AuditEvent> audited(final String owner, final AuditLogEvent event) {
        return storage().auditLog().find(owner, event.getAuditLogEvent(), null, null, 0, 100);
    }

    @Test
    default void ledgerSearchesAreAuditedWithAHashOfTheTermNeverTheTerm() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789"));
        final String term = "search-" + UUID.randomUUID();

        storage().ledger().listChains("req", owner, term, new Listings.Sort("timestamp", true), 0, 10, "test");

        final List<AuditLogService.AuditEvent> queries = audited(owner, AuditLogEvent.REDACTION_LEDGER_QUERY);
        assertEquals(1, queries.size());
        assertEquals("searchTermHash: " + DigestUtils.sha256Hex(term), queries.get(0).details());
        assertFalse(storage().storageContains(term), "the search term is stored nowhere");
    }

    @Test
    default void ledgerDeletionsAndRefusedDeletionsAreAudited() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789"));
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc",
                "litigation", owner, owner).getStatusCode());

        assertFalse(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        final List<AuditLogService.AuditEvent> blocked = audited(owner, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION);
        assertEquals(1, blocked.size());
        assertTrue(blocked.get(0).details().contains("case-1"), blocked.get(0).details());
        assertTrue(audited(owner, AuditLogEvent.REDACTION_LEDGER_DELETED).isEmpty());

        assertTrue(storage().legalHolds().release("req", "case-1", owner).isSuccessful());
        assertTrue(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        final List<AuditLogService.AuditEvent> deleted = audited(owner, AuditLogEvent.REDACTION_LEDGER_DELETED);
        assertEquals(1, deleted.size());
        assertEquals("documentId: doc", deleted.get(0).details());
    }

    /** An entry made at {@code at}, following {@code previousHash}. */
    private LedgerEntity entryAt(final String owner, final String documentId, final String previousHash, final Date at)
            throws Exception {
        final LedgerEntity entry = new LedgerEntity(owner, documentId, "123-45-6789", "{{{REDACTED-ssn}}}", 0, "input-hash",
                previousHash, "file.pdf", "ssn", "policy", 0, "policy-hash");
        entry.setTimestamp(at);
        entry.setHash(entry.calculateHash());
        return entry;
    }

    /** A chain of one entry made at {@code at}, completed, as if it had been completed then too. */
    private void completedChainAt(final String owner, final String documentId, final Date at) throws Exception {
        storage().ledger().addTransaction(entryAt(owner, documentId, LedgerDataService.GENESIS, at));
        storage().ledger().completeChain(owner, documentId);
        storage().backdateLedgerChainCompletion(owner, documentId, at);
    }

    private static Date daysAgo(final int days) {
        return new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
    }

    @Test
    default void ledgerPurgeByAgeDeletesOnlyChainsCompletedAndLastWrittenBeforeTheCutoff() throws Exception {
        final String owner = RecordIds.newId();
        final Date old = daysAgo(10);
        final LedgerEntity head = entryAt(owner, "old", LedgerDataService.GENESIS, old);
        storage().ledger().addTransaction(head);
        storage().ledger().addTransaction(entryAt(owner, "old", head.getHash(), old));
        storage().ledger().completeChain(owner, "old");
        storage().backdateLedgerChainCompletion(owner, "old", old);

        // Completed long ago, but written to recently: kept whole.
        final LedgerEntity spanningHead = entryAt(owner, "spanning", LedgerDataService.GENESIS, old);
        storage().ledger().addTransaction(spanningHead);
        storage().ledger().addTransaction(entryAt(owner, "spanning", spanningHead.getHash(), new Date()));
        storage().ledger().completeChain(owner, "spanning");
        storage().backdateLedgerChainCompletion(owner, "spanning", old);

        completedChainAt(owner, "recent", old);
        storage().backdateLedgerChainCompletion(owner, "recent", new Date());
        storage().ledger().addTransaction(entryAt(owner, "unfinished", LedgerDataService.GENESIS, old));
        final String other = RecordIds.newId();
        completedChainAt(other, "old", old);

        final ServiceResponse purged = storage().anotherInstance().ledger().deleteChainsByUserIdAndOlderThan("req", owner, 1);
        assertTrue(purged.isSuccessful(), purged.getMessage());
        assertTrue(purged.getMessage().contains("2 ledger entries in 1 completed chains"), purged.getMessage());

        assertFalse(storage().ledger().chainExists(owner, "old"));
        assertEquals(2, storage().ledger().getChain(owner, "spanning").size());
        assertEquals(1, storage().ledger().getChain(owner, "recent").size());
        assertEquals(1, storage().ledger().getChain(owner, "unfinished").size());
        assertEquals(1, storage().ledger().getChain(other, "old").size(), "another owner's chains are not purged");
    }

    @Test
    default void ledgerAPurgeBlockedByALegalHoldDeletesNothing() throws Exception {
        final String owner = RecordIds.newId();
        completedChainAt(owner, "held", daysAgo(10));
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_USER, owner, "litigation",
                owner, owner).getStatusCode());
        assertEquals(423, storage().ledger().deleteChainsByUserIdAndOlderThan("req", owner, 1).getStatusCode());
        assertEquals(1, storage().ledger().getChain(owner, "held").size());
    }

    @Test
    default void ledgerAnOpenChainCannotBeDeletedBetweenAppends() throws Exception {
        final String owner = RecordIds.newId();
        storage().ledger().initializeLedger(owner, "doc", "input-hash", "file.pdf", "policy", 0, "policy-hash");
        final LedgerEntity next = entry(owner, "doc", "123-45-6789");
        assertEquals(409, storage().anotherInstance().ledger().deleteByDocumentId("req", owner, "doc", "test").getStatusCode());
        storage().ledger().addTransaction(next);
        storage().ledger().completeChain(owner, "doc");
        assertTrue(storage().ledger().validateChain(owner, "doc").valid());
        assertEquals(2, storage().ledger().getChain(owner, "doc").size());
    }

    @Test
    default void ledgerADeletedOrPurgedChainTakesNoLateAppendsAndNoNewGenesis() throws Exception {
        final String owner = RecordIds.newId();
        storage().ledger().initializeLedger(owner, "deleted", "input-hash", "file.pdf", "policy", 0, "policy-hash");
        final LedgerEntity late = entry(owner, "deleted", "123-45-6789");
        storage().ledger().completeChain(owner, "deleted");
        assertTrue(storage().anotherInstance().ledger().deleteByDocumentId("req", owner, "deleted", "test").isSuccessful());
        assertThrows(IllegalStateException.class, () -> storage().ledger().addTransaction(late));
        assertThrows(IllegalStateException.class, () -> storage().ledger().initializeLedger(owner, "deleted", "input-hash",
                "file.pdf", "policy", 0, "policy-hash"));
        assertThrows(IllegalStateException.class, () -> storage().ledger().completeChain(owner, "deleted"));
        assertTrue(storage().ledger().getChain(owner, "deleted").isEmpty());
        assertTrue(storage().ledger().deleteByDocumentId("req", owner, "deleted", "test").isSuccessful(), "deleting again is harmless");

        final Date old = daysAgo(10);
        final LedgerEntity head = entryAt(owner, "purged", LedgerDataService.GENESIS, old);
        storage().ledger().addTransaction(head);
        storage().ledger().completeChain(owner, "purged");
        storage().backdateLedgerChainCompletion(owner, "purged", old);
        final LedgerEntity next = entryAt(owner, "purged", head.getHash(), new Date());
        assertThrows(IllegalStateException.class, () -> storage().anotherInstance().ledger().addTransaction(next),
                "a completed chain takes no more");
        assertTrue(storage().ledger().deleteChainsByUserIdAndOlderThan("req", owner, 1).isSuccessful());
        assertThrows(IllegalStateException.class, () -> storage().anotherInstance().ledger().addTransaction(next),
                "nor does a purged one");
        assertTrue(storage().ledger().getChain(owner, "purged").isEmpty());
    }

    @Test
    default void ledgerChainsReadBackInWriteOrderWhateverTheEntriesIdsAndTimestamps() throws Exception {
        final String owner = RecordIds.newId();
        storage().ledger().initializeLedger(owner, "doc", "input-hash", "file.pdf", "policy", 0, "policy-hash");
        final Date sameMillisecond = new Date();
        for (int i = 0; i < 8; i++) {
            final LedgerEntity entry = entry(owner, "doc", "token-" + i);
            // Ids that sort in the reverse of write order, as ids made in one second on different instances, or
            // copied from another storage, may; and one shared timestamp.
            entry.setId(String.format("%024x", 1000 - i));
            entry.setTimestamp(sameMillisecond);
            entry.setHash(entry.calculateHash());
            (i % 2 == 0 ? storage().ledger() : storage().anotherInstance().ledger()).addTransaction(entry);
        }
        storage().ledger().completeChain(owner, "doc");

        final List<LedgerEntity> chain = storage().anotherInstance().ledger().getChain(owner, "doc");
        assertEquals(9, chain.size());
        for (int position = 0; position < chain.size(); position++) {
            assertEquals(position, chain.get(position).getChainPosition());
        }
        assertEquals("token-7", chain.get(8).getToken());
        assertEquals("token-7", storage().ledger().getLatestTransaction(owner, "doc").getToken());
        assertTrue(storage().ledger().validateChain(owner, "doc").valid());
    }

    @Test
    default void ledgerAnEntryThatDoesNotFollowTheLatestEntryIsRefusedAndTheChainStaysOpen() throws Exception {
        final String owner = RecordIds.newId();
        storage().ledger().initializeLedger(owner, "doc", "input-hash", "file.pdf", "policy", 0, "policy-hash");
        // Two writers read the chain at the same point; the first appends.
        final LedgerEntity first = entry(owner, "doc", "first");
        final LedgerEntity stale = entry(owner, "doc", "second");
        storage().ledger().addTransaction(first);

        assertThrows(StaleLedgerEntryException.class, () -> storage().anotherInstance().ledger().addTransaction(stale));
        assertThrows(StaleLedgerEntryException.class, () -> storage().ledger().initializeLedger(owner, "doc", "input-hash",
                "file.pdf", "policy", 0, "policy-hash"), "a genesis entry only starts an empty chain");
        assertEquals(2, storage().ledger().getChain(owner, "doc").size(), "nothing was written");

        // The chain is still open: the writer reads it again and appends.
        storage().anotherInstance().ledger().addTransaction(entry(owner, "doc", "second"));
        storage().ledger().completeChain(owner, "doc");
        assertEquals(3, storage().ledger().getChain(owner, "doc").size());
        assertTrue(storage().ledger().validateChain(owner, "doc").valid());
    }

    @Test
    default void ledgerConcurrentAppendersThatRetryNeverForkAChain() throws Exception {
        final String owner = RecordIds.newId();
        storage().ledger().initializeLedger(owner, "doc", "input-hash", "file.pdf", "policy", 0, "policy-hash");
        final AtomicInteger next = new AtomicInteger();
        StorageContract.concurrently(4, () -> {
            final LedgerDataService ledger = next.getAndIncrement() % 2 == 0 ? storage().ledger()
                    : storage().anotherInstance().ledger();
            for (int i = 0; i < 5; i++) {
                for (int attempt = 0; ; attempt++) {
                    try {
                        ledger.addTransaction(entry(owner, "doc", "token"));
                        break;
                    } catch (final IllegalStateException busy) {
                        // A StaleLedgerEntryException (another writer appended first) or a chain another writer
                        // holds: read the chain again and retry.
                        assertTrue(attempt < 10_000, "an append keeps being refused: " + busy.getMessage());
                        Thread.onSpinWait();
                    }
                }
            }
            return null;
        });
        storage().ledger().completeChain(owner, "doc");
        assertEquals(21, storage().ledger().getChain(owner, "doc").size());
        assertTrue(storage().ledger().validateChain(owner, "doc").valid(), "one unbroken chain");
    }

    @Test
    default void ledgerARefusedFirstEntryLeavesTheEmptyChainWithNoLatestEntryTime() throws Exception {
        final String owner = RecordIds.newId();
        // The first entry of an empty chain must be a genesis entry; this one is refused.
        final LedgerEntity notGenesis = entryAt(owner, "empty", "not-the-genesis", daysAgo(10));
        assertThrows(StaleLedgerEntryException.class, () -> storage().ledger().addTransaction(notGenesis));

        // The chain has no entries, so it has no time of a latest entry, and a purge by age never treats it as old:
        // purging it would fence its document id against any later chain.
        storage().ledger().completeChain(owner, "empty");
        storage().backdateLedgerChainCompletion(owner, "empty", daysAgo(10));
        final ServiceResponse purged = storage().ledger().deleteChainsByUserIdAndOlderThan("req", owner, 1);
        assertTrue(purged.isSuccessful(), purged.getMessage());
        assertTrue(purged.getMessage().contains("in 0 completed chains"), purged.getMessage());
    }

}
