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
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.services.webhook.WebhookService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.util.Date;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link WebhookDeliveryDataService} against a real (in-memory) MongoDB.
 * These exercise the atomic {@code findOneAndUpdate} due-claim (including the attempt increment, the
 * due/not-yet {@code next_attempt_at} branches, and the ordering of the claim), the delivered
 * transition with its TTL marker, and the reschedule-or-fail backoff/terminal-failure branches end
 * to end — behavior the mock-based unit tests can only approximate.
 */
class WebhookDeliveryDataServiceIT extends AbstractMongoIT {

    private WebhookDeliveryDataService service;
    private final TestEncryptionService encryption = new TestEncryptionService();

    @BeforeEach
    void setUpService() {
        service = new WebhookDeliveryDataService(mongoClient, encryption, mock(AuditEventPublisher.class));
    }

    private ObjectId savePending(final String documentId, final Date nextAttemptAt) {
        final WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setUserId(new ObjectId());
        entity.setDocumentId(documentId);
        entity.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
        entity.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
        entity.setUrl("https://example.com/hook");
        entity.setSecret("s");
        entity.setPayload("{}");
        entity.setAttempts(0);
        entity.setNextAttemptAt(nextAttemptAt);
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        return service.save(entity);
    }

    private WebhookDeliveryEntity reload(final ObjectId id) {
        final MongoCollection<Document> collection =
                mongoClient.getDatabase("philter").getCollection("webhook_deliveries");
        final Document document = collection.find(Filters.eq("_id", id)).first();
        return document != null ? WebhookDeliveryEntity.fromDocument(document, encryption) : null;
    }

    @Test
    void onlyDuePendingDeliveriesAreClaimed() {
        final Date now = new Date();
        assertNull(service.claimNextDue(now));
        savePending("future", new Date(now.getTime() + 60_000));
        assertNull(service.claimNextDue(now));
        final ObjectId due = savePending("due", now);
        final var claimed = service.claimNextDue(now);
        assertEquals(due, claimed.getId());
        assertEquals(WebhookDeliveryEntity.STATUS_PROCESSING, claimed.getStatus());
        assertEquals(1, claimed.getAttempts());
        assertNotNull(claimed.getClaimToken());
        assertTrue(claimed.getClaimExpiresAt().after(now));
        assertEquals(claimed.getClaimToken(), reload(due).getClaimToken());
        assertNull(service.claimNextDue(now));
    }

    @Test
    void activeClaimsDoNotBlockOtherDueDeliveries() {
        final Date now = new Date();
        final ObjectId later = savePending("later", new Date(now.getTime() - 1000));
        final ObjectId earlier = savePending("earlier", new Date(now.getTime() - 5000));
        final var first = service.claimNextDue(now);
        final var second = service.claimNextDue(now);
        assertEquals(earlier, first.getId());
        assertEquals(later, second.getId());
        assertNull(service.claimNextDue(now));
        assertTrue(service.markDelivered(first.getId(), first.getClaimToken()));
        assertTrue(service.markDelivered(second.getId(), second.getClaimToken()));
        assertNull(reload(earlier).getClaimToken());
        assertNull(reload(earlier).getClaimExpiresAt());
        assertNull(reload(earlier).getNextAttemptAt());
        assertNotNull(reload(earlier).getDeliveredAt());
    }

    @Test
    void concurrentWorkersHaveExactlyOneWinner() throws Exception {
        final Date now = new Date();
        final ObjectId id = savePending("contended", now);
        final var other = new WebhookDeliveryDataService(mongoClient, encryption, mock(AuditEventPublisher.class));
        final var start = new java.util.concurrent.CountDownLatch(1);
        try (final var pool = java.util.concurrent.Executors.newFixedThreadPool(12)) {
            final var futures = new java.util.ArrayList<java.util.concurrent.Future<WebhookDeliveryEntity>>();
            for (int i = 0; i < 12; i++) {
                final var workerService = i % 2 == 0 ? service : other;
                futures.add(pool.submit(() -> {
                    start.await();
                    return workerService.claimNextDue(now);
                }));
            }
            start.countDown();
            int winners = 0;
            for (final var future : futures) {
                final var claimed = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (claimed != null) {
                    winners++;
                    assertEquals(id, claimed.getId());
                }
            }
            assertEquals(1, winners);
            assertEquals(1, reload(id).getAttempts());
        }
    }

    @Test
    void expiredClaimIsRecoveredAndOldWorkerCannotChangeItsOutcome() {
        final Date now = new Date();
        final ObjectId id = savePending("recovered", now);
        final var old = service.claimNextDue(now);
        final var current = service.claimNextDue(old.getClaimExpiresAt());
        assertEquals(id, current.getId());
        assertEquals(2, current.getAttempts());
        assertNotEquals(old.getClaimToken(), current.getClaimToken());
        assertFalse(service.markDelivered(id, old.getClaimToken()));
        assertFalse(service.rescheduleOrFail(id, old.getClaimToken(), old.getAttempts(), "old failure"));
        assertEquals(current.getClaimToken(), reload(id).getClaimToken());
        assertTrue(service.markDelivered(id, current.getClaimToken()));
        assertFalse(service.rescheduleOrFail(id, old.getClaimToken(), old.getAttempts(), "late failure"));
        assertFalse(service.rescheduleOrFail(id, current.getClaimToken(), current.getAttempts(), "duplicate failure"));
        assertEquals(WebhookDeliveryEntity.STATUS_DELIVERED, reload(id).getStatus());
    }

    @Test
    void expiredWorkerCannotFinishEvenBeforeAnotherWorkerClaims() {
        final Date now = new Date();
        final ObjectId id = savePending("expired", now);
        final var claim = service.claimNextDue(now);
        collection().updateOne(Filters.eq("_id", id), new Document("$set", new Document("claim_expires_at", new Date(0))));
        assertFalse(service.markDelivered(id, claim.getClaimToken()));
        assertFalse(service.rescheduleOrFail(id, claim.getClaimToken(), 1, "late"));
        assertEquals(WebhookDeliveryEntity.STATUS_PROCESSING, reload(id).getStatus());
    }

    @Test
    void retryBackoffReleasesClaimAndUsesFreshToken() {
        final Date now = new Date();
        final ObjectId id = savePending("retry", now);
        final var first = service.claimNextDue(now);
        assertFalse(service.rescheduleOrFail(id, first.getClaimToken(), 2, "wrong attempt count"));
        final long before = System.currentTimeMillis();
        assertTrue(service.rescheduleOrFail(id, first.getClaimToken(), 1, "transient"));
        final var pending = reload(id);
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, pending.getStatus());
        assertEquals("transient", pending.getLastError());
        assertNull(pending.getClaimToken());
        assertNull(pending.getClaimExpiresAt());
        assertTrue(pending.getNextAttemptAt().getTime() >= before + 30_000);
        assertNull(service.claimNextDue(now));
        final var retry = service.claimNextDue(pending.getNextAttemptAt());
        assertEquals(2, retry.getAttempts());
        assertNotEquals(first.getClaimToken(), retry.getClaimToken());
        assertFalse(service.markDelivered(id, first.getClaimToken()));
    }

    @Test
    void finalFailedAttemptIsTerminalAndCannotBeOverwritten() {
        final Date now = new Date();
        final ObjectId id = savePending("last", now);
        collection().updateOne(Filters.eq("_id", id), new Document("$set", new Document("attempts", 7)));
        final var last = service.claimNextDue(now);
        assertEquals(WebhookDeliveryDataService.MAX_ATTEMPTS, last.getAttempts());
        assertTrue(service.rescheduleOrFail(id, last.getClaimToken(), last.getAttempts(), "exhausted"));
        assertFalse(service.markDelivered(id, last.getClaimToken()));
        final var failed = reload(id);
        assertEquals(WebhookDeliveryEntity.STATUS_FAILED, failed.getStatus());
        assertNull(failed.getClaimToken());
        assertNull(failed.getClaimExpiresAt());
        assertNull(failed.getNextAttemptAt());
        assertNull(service.claimNextDue(new Date(Long.MAX_VALUE)));
    }

    @Test
    void repeatedAbandonedClaimsStopAtAttemptLimit() {
        Date now = new Date();
        final ObjectId id = savePending("abandoned", now);
        for (int attempt = 1; attempt <= WebhookDeliveryDataService.MAX_ATTEMPTS; attempt++) {
            final var claim = service.claimNextDue(now);
            assertNotNull(claim);
            assertEquals(attempt, claim.getAttempts());
            now = claim.getClaimExpiresAt();
        }
        assertNull(service.claimNextDue(now));
        final var failed = reload(id);
        assertEquals(WebhookDeliveryEntity.STATUS_FAILED, failed.getStatus());
        assertEquals(WebhookDeliveryDataService.MAX_ATTEMPTS, failed.getAttempts());
        assertNull(failed.getClaimToken());
        assertNull(failed.getClaimExpiresAt());
        assertNull(failed.getNextAttemptAt());
    }

    @Test
    void storedSecretIsEncryptedAndRetryUsesOriginalSigningSecret() {
        final Date now = new Date();
        final ObjectId id = savePending("encrypted", now);
        final Document stored = collection().find(Filters.eq("_id", id)).first();
        assertNotEquals("s", stored.getString("secret"));
        assertNotNull(stored.getString("secret_encrypted_key"));
        final var first = service.claimNextDue(now);
        final String expected = WebhookService.sign(123, first.getPayload(), "s");
        assertEquals(expected, WebhookService.sign(123, first.getPayload(), first.getSecret()));
        assertTrue(service.rescheduleOrFail(id, first.getClaimToken(), 1, "retry"));
        final var retry = service.claimNextDue(reload(id).getNextAttemptAt());
        assertEquals("s", retry.getSecret());
        assertEquals(stored.getString("secret"), collection().find(Filters.eq("_id", id)).first().getString("secret"));
    }

    @Test
    void missingClaimTokensCannotMutateDeliveries() {
        final ObjectId id = savePending("pending", new Date());
        assertThrows(IllegalArgumentException.class, () -> service.markDelivered(id, null));
        assertThrows(IllegalArgumentException.class, () -> service.rescheduleOrFail(id, "", 1, "failure"));
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, reload(id).getStatus());
    }

    @Test
    void readerRejectsSecretWithoutEncryptedKey() {
        assertThrows(IllegalStateException.class,
                () -> WebhookDeliveryEntity.fromDocument(new Document("secret", "unprotected-secret"), encryption));
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase("philter").getCollection("webhook_deliveries");
    }
}
