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

import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerRetentionIT extends AbstractMongoIT {
    private final String owner = MongoIds.newId();
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
        return new MongoLedgerDataService(mongoClient, encryption, mock(AuditEventPublisher.class), holds, signer);
    }

    private LedgerEntity entry(final String user, final String doc, final String previous, final Date date)
            throws Exception {
        final var entry = new LedgerEntity(user, doc, "token", "replacement", 0, "document-hash",
                previous, "file", "", "policy", 1, "policy-hash");
        entry.setTimestamp(date);
        entry.setHash(entry.calculateHash());
        return entry;
    }

    @Test
    void activeWriterPreventsSealingAndAgePurge() throws Exception {
        final var head = entry(owner, "writing", LedgerDataService.GENESIS, old);
        service.addTransaction(head);
        final var next = entry(owner, "writing", head.getHash(), old);
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            writing.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return invocation.callRealMethod();
        }).when(encryption).encrypt(anyString(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var pending = executor.submit(() -> service.addTransaction(next));
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
        final var head = entry(owner, "failed", LedgerDataService.GENESIS, old);
        service.addTransaction(head);
        doThrow(new IllegalStateException("encryption unavailable"))
                .when(encryption).encrypt(anyString(), anyString());
        final var next = entry(owner, "failed", head.getHash(), old);
        assertThrows(IllegalStateException.class, () -> service.addTransaction(next));
        assertThrows(IllegalStateException.class, () -> service.completeChain(owner, "failed"));
        service.deleteChainsByUserIdAndOlderThan("req", owner, 0);
        assertEquals(1, service.getChain(owner, "failed").size());
    }

}
