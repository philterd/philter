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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerRetentionIT extends AbstractMongoIT {
    private final ObjectId owner = new ObjectId();
    private final Date old = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10));
    private TestEncryptionService encryption;
    private SigningService signer;
    private LegalHoldDataService holds;
    private LedgerDataService service;

    @BeforeEach
    void setup() throws Exception {
        encryption = spy(new TestEncryptionService());
        signer = mock(SigningService.class);
        when(signer.signLedgerEntry(anyString())).thenReturn(new SigningService.LedgerSignature("signature", "key"));
        holds = mock(LegalHoldDataService.class);
        service = newService();
    }

    private LedgerDataService newService() {
        return new LedgerDataService(mongoClient, encryption, mock(AuditEventPublisher.class), holds, signer);
    }

    private MongoCollection<Document> states() {
        return mongoClient.getDatabase("philter").getCollection("ledger_chains");
    }

    private LedgerEntity entry(final ObjectId user, final String doc, final String previous, final Date date)
            throws Exception {
        final var entry = new LedgerEntity(user, doc, "token", "replacement", 0, "document-hash",
                previous, "file", "", "policy", 1, "policy-hash");
        entry.setTimestamp(date);
        entry.setHash(entry.calculateHash());
        return entry;
    }

    private void sealOld(final ObjectId user, final String doc) {
        service.completeChain(user, doc);
        states().updateOne(new Document("_id", user + ":" + doc),
                new Document("$set", new Document("completed_at", old)));
    }

    @Test
    void purgeDeletesWholeEligibleChainAndPreservesOtherOwner() throws Exception {
        final var head = entry(owner, "shared", LedgerDataService.GENESIS, old);
        service.save(head);
        service.save(entry(owner, "shared", head.getHash(), old));
        sealOld(owner, "shared");
        final var other = new ObjectId();
        service.save(entry(other, "shared", LedgerDataService.GENESIS, old));
        sealOld(other, "shared");

        final var result = service.deleteChainsByUserIdAndOlderThan("req", owner, 1);

        assertTrue(result.isSuccessful());
        assertTrue(result.getMessage().contains("2 ledger entries in 1 completed chains"));
        assertTrue(service.getChain(owner, "shared").isEmpty());
        assertEquals(1, service.getChain(other, "shared").size());
    }

    @Test
    void chainSpanningCutoffKeepsItsGenesisAndSuffix() throws Exception {
        final var head = entry(owner, "spanning", LedgerDataService.GENESIS, old);
        service.save(head);
        service.save(entry(owner, "spanning", head.getHash(), new Date()));
        sealOld(owner, "spanning");
        service.deleteChainsByUserIdAndOlderThan("req", owner, 1);
        assertEquals(2, service.getChain(owner, "spanning").size());
        assertEquals(1, service.countChainsByUserId(owner));
    }

    @Test
    void recentlyCompletedAndUnfinishedChainsAreRetained() throws Exception {
        service.save(entry(owner, "recent", LedgerDataService.GENESIS, old));
        service.completeChain(owner, "recent");
        service.save(entry(owner, "unfinished", LedgerDataService.GENESIS, old));
        service.deleteChainsByUserIdAndOlderThan("req", owner, 1);
        assertEquals(1, service.getChain(owner, "recent").size());
        assertEquals(1, service.getChain(owner, "unfinished").size());
    }

    @Test
    void completedAndPurgedChainsRejectAppendsFromAnotherService() throws Exception {
        final var head = entry(owner, "sealed", LedgerDataService.GENESIS, old);
        service.save(head);
        sealOld(owner, "sealed");
        final var otherWorker = newService();
        final var next = entry(owner, "sealed", head.getHash(), new Date());
        assertThrows(IllegalStateException.class, () -> otherWorker.save(next));
        service.deleteChainsByUserIdAndOlderThan("req", owner, 1);
        assertThrows(IllegalStateException.class, () -> otherWorker.save(next));
        assertTrue(service.getChain(owner, "sealed").isEmpty());
    }

    @Test
    void activeWriterPreventsSealingAndAgePurge() throws Exception {
        final var head = entry(owner, "writing", LedgerDataService.GENESIS, old);
        service.save(head);
        final var next = entry(owner, "writing", head.getHash(), old);
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(encryption).encrypt(anyString(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var pending = executor.submit(() -> service.save(next));
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));
                final var purger = newService();
                assertThrows(IllegalStateException.class, () -> purger.completeChain(owner, "writing"));
                purger.deleteChainsByUserIdAndOlderThan("req", owner, 0);
                assertEquals(1, service.getChain(owner, "writing").size());
            } finally {
                release.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
        }
        service.completeChain(owner, "writing");
        assertEquals(2, service.getChain(owner, "writing").size());
    }

    @Test
    void failedInsertCannotBeSealedOrPurged() throws Exception {
        service.save(entry(owner, "failed", LedgerDataService.GENESIS, old));
        doThrow(new IllegalStateException("encryption unavailable"))
                .when(encryption).encrypt(anyString(), anyString());
        final var next = entry(owner, "failed", "previous", old);
        assertThrows(IllegalStateException.class, () -> service.save(next));
        assertThrows(IllegalStateException.class, () -> service.completeChain(owner, "failed"));
        service.deleteChainsByUserIdAndOlderThan("req", owner, 0);
        assertEquals(1, service.getChain(owner, "failed").size());
    }

    @Test
    void holdBlocksWholeChainPurge() throws Exception {
        service.save(entry(owner, "held", LedgerDataService.GENESIS, old));
        sealOld(owner, "held");
        when(holds.hasAnyHold(owner)).thenReturn(true);
        assertEquals(423, service.deleteChainsByUserIdAndOlderThan("req", owner, 1).getStatusCode());
        assertEquals(1, service.getChain(owner, "held").size());
    }
}
