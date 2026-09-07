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
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegalHoldConcurrencyIT extends AbstractMongoIT {
    private ObjectId owner;
    private MongoDatabase isolated;
    private LegalHoldDataService holds;
    private LedgerDataService ledger;

    @BeforeEach
    void setupServices() {
        final String uri = System.getProperty("philter.test.mongoUri");
        if (uri != null) {
            mongoClient.close();
            mongoClient = spy(MongoClients.create(uri));
            isolated = mongoClient.getDatabase("philter_a13_" + new ObjectId());
            doReturn(isolated).when(mongoClient).getDatabase("philter");
        }
        owner = new ObjectId();
        holds = new LegalHoldDataService(mongoClient, mock(AuditEventPublisher.class));
        ledger = ledger(holds);
        mongoClient.getDatabase("philter").getCollection("ledger").insertOne(
                new Document("user_id", owner).append("document_id", "doc").append("timestamp", new Date(0)));
        mongoClient.getDatabase("philter").getCollection("ledger_chains").insertOne(
                new Document("_id", owner + ":doc").append("user_id", owner).append("document_id", "doc")
                        .append("state", "complete").append("completed_at", new Date(0)).append("latest_entry_at", new Date(0)));
    }

    @AfterEach
    void cleanupRealDatabase() {
        if (isolated != null) isolated.drop();
    }

    private LedgerDataService ledger(final LegalHoldDataService holdService) {
        return new LedgerDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class),
                holdService, mock(SigningService.class));
    }

    private ServiceResponse delete(final LedgerDataService service, final int path) {
        return switch (path) {
            case 0 -> service.deleteByDocumentId("req", owner, "doc", "test");
            case 1 -> service.deleteChainsByUserIdAndOlderThan("req", owner, 1);
            default -> service.deleteAllByUserId("req", owner);
        };
    }

    private ServiceResponse create(final LegalHoldDataService service, final boolean userScope) {
        return service.create("req", "hold", userScope ? LegalHoldEntity.SCOPE_USER : LegalHoldEntity.SCOPE_DOCUMENT_CHAIN,
                userScope ? owner.toHexString() : "doc", "reason", owner, owner);
    }

    private long entries() {
        return mongoClient.getDatabase("philter").getCollection("ledger")
                .countDocuments(new Document("user_id", owner));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void deletionHoldingGuardCannotBeOvertakenByEitherHoldScope(final int path) throws Exception {
        final var checked = new CountDownLatch(1);
        final var resume = new CountDownLatch(1);
        final var checkingHolds = spy(holds);
        if (path == 0) {
            doAnswer(inv -> { checked.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)); return false; })
                    .when(checkingHolds).isProtectedDocument(owner, "doc");
        } else {
            doAnswer(inv -> { checked.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)); return false; })
                    .when(checkingHolds).hasAnyHold(owner);
        }
        final var deleting = ledger(checkingHolds);
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var pending = executor.submit(() -> delete(deleting, path));
            try {
                assertTrue(checked.await(5, TimeUnit.SECONDS));
                final var otherInstance = new LegalHoldDataService(mongoClient, mock(AuditEventPublisher.class));
                assertEquals(409, create(otherInstance, false).getStatusCode());
                assertEquals(409, create(otherInstance, true).getStatusCode());
                assertNull(otherInstance.findByReference("hold", owner));
                assertEquals(1, entries());
            } finally {
                resume.countDown();
            }
            assertTrue(pending.get(5, TimeUnit.SECONDS).isSuccessful());
        }
        assertEquals(0, entries());
        assertEquals(201, create(holds, true).getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void holdCreationExcludesAllDeletionsUntilHoldIsVisible(final boolean userScope) throws Exception {
        final var inserted = new CountDownLatch(1);
        final var resume = new CountDownLatch(1);
        final var audit = mock(AuditEventPublisher.class);
        doAnswer(inv -> { inserted.countDown(); assertTrue(resume.await(5, TimeUnit.SECONDS)); return null; })
                .when(audit).auditEvent(any(), eq(AuditLogEvent.LEGAL_HOLD_SET), any(), any(), any(), any());
        final var setting = new LegalHoldDataService(mongoClient, audit);
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var pending = executor.submit(() -> create(setting, userScope));
            try {
                assertTrue(inserted.await(5, TimeUnit.SECONDS));
                for (int path = 0; path < 3; path++) assertEquals(409, delete(ledger, path).getStatusCode());
                assertEquals(409, holds.release("req", "hold", owner).getStatusCode());
                assertEquals(1, entries());
            } finally {
                resume.countDown();
            }
            assertEquals(201, pending.get(5, TimeUnit.SECONDS).getStatusCode());
        }
        for (int path = 0; path < 3; path++) assertEquals(423, delete(ledger, path).getStatusCode());
        assertEquals(1, entries());
    }

    @Test
    void uncertainDeletionLeavesGuardHeldAcrossServiceRestart() {
        final var broken = spy(holds);
        doThrow(new IllegalStateException("uncertain operation")).when(broken).hasAnyHold(owner);
        assertThrows(IllegalStateException.class, () -> ledger(broken).deleteAllByUserId("req", owner));
        final var restarted = new LegalHoldDataService(mongoClient, mock(AuditEventPublisher.class));
        assertEquals(409, create(restarted, true).getStatusCode());
        assertEquals(409, delete(ledger(restarted), 2).getStatusCode());
        final var guard = mongoClient.getDatabase("philter").getCollection("evidence_operation_guards")
                .find(new Document("_id", owner)).first();
        assertNotNull(guard.getString("token"));
        assertFalse(guard.containsKey("expires_at"));
    }

    @Test
    void otherOwnersAndUnrelatedDocumentHoldsRemainIndependent() {
        assertEquals(201, holds.create("req", "unrelated", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN,
                "other-doc", "", owner, owner).getStatusCode());
        assertTrue(delete(ledger, 0).isSuccessful());
        final var guard = new EvidenceOperationGuard(mongoClient);
        assertThrows(IllegalStateException.class, () -> guard.execute(owner, "failed",
                () -> { throw new IllegalStateException("interrupted"); }));
        final var other = new ObjectId();
        assertEquals(201, holds.create("req", "other", LegalHoldEntity.SCOPE_USER,
                other.toHexString(), "", other, other).getStatusCode());
    }

    @Test
    void releasingOneHoldDoesNotRemoveAnotherAndDirectWritesAreRejected() {
        assertEquals(201, create(holds, true).getStatusCode());
        assertEquals(201, holds.create("req", "second", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN,
                "doc", "", owner, owner).getStatusCode());
        assertEquals(200, holds.release("req", "hold", owner).getStatusCode());
        assertEquals(423, delete(ledger, 0).getStatusCode());
        assertEquals(200, holds.release("req", "second", owner).getStatusCode());
        assertTrue(delete(ledger, 0).isSuccessful());
        assertThrows(UnsupportedOperationException.class, () -> holds.save(new LegalHoldEntity()));
        assertThrows(UnsupportedOperationException.class, () -> holds.update(new LegalHoldEntity()));
    }
}
