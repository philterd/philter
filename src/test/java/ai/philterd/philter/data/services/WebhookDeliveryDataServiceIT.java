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
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @BeforeEach
    void setUpService() {
        service = new WebhookDeliveryDataService(mongoClient, mock(AuditEventPublisher.class));
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
        return document != null ? WebhookDeliveryEntity.fromDocument(document) : null;
    }

    @Test
    void claimNextDueReturnsNullWhenEmpty() {
        assertNull(service.claimNextDue(new Date()));
    }

    @Test
    void claimNextDueIgnoresDeliveriesNotYetDue() {
        final Date now = new Date();
        // Scheduled in the future relative to the claim time.
        savePending("future", new Date(now.getTime() + 60_000));

        // Not-yet-due branch: nothing is claimed.
        assertNull(service.claimNextDue(now));
    }

    @Test
    void claimNextDueClaimsDueDeliveryAndIncrementsAttempts() {
        final Date now = new Date();
        final ObjectId id = savePending("due", new Date(now.getTime() - 1000));

        final WebhookDeliveryEntity claimed = service.claimNextDue(now);
        assertNotNull(claimed);
        assertEquals(id, claimed.getId());
        // attempts is incremented from 0 -> 1 by the claim.
        assertEquals(1, claimed.getAttempts());
        // It remains PENDING (status is only changed on delivery/failure), and updated_at is set.
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, claimed.getStatus());
        assertNotNull(claimed.getUpdatedAt());
    }

    @Test
    void claimNextDueReturnsEarliestDueFirst() {
        final Date now = new Date();
        savePending("later", new Date(now.getTime() - 1000));
        final ObjectId earlierId = savePending("earlier", new Date(now.getTime() - 5000));

        // The delivery with the earliest next_attempt_at is claimed first.
        final WebhookDeliveryEntity first = service.claimNextDue(now);
        assertNotNull(first);
        assertEquals("earlier", first.getDocumentId());

        // claimNextDue does not change next_attempt_at, so the earliest remains due and would be
        // returned again. Take it out of contention (deliver it) so the next claim returns "later".
        service.markDelivered(earlierId);

        final WebhookDeliveryEntity second = service.claimNextDue(now);
        assertNotNull(second);
        assertEquals("later", second.getDocumentId());

        // After delivering the later one too, nothing remains due.
        service.markDelivered(second.getId());
        assertNull(service.claimNextDue(now));
    }

    @Test
    void markDeliveredSetsTerminalDeliveredStateAndClearsNextAttempt() {
        final Date now = new Date();
        final ObjectId id = savePending("doc-1", new Date(now.getTime() - 1000));

        service.markDelivered(id);

        final WebhookDeliveryEntity delivered = reload(id);
        assertEquals(WebhookDeliveryEntity.STATUS_DELIVERED, delivered.getStatus());
        assertNotNull(delivered.getDeliveredAt());
        assertNotNull(delivered.getUpdatedAt());
        assertNull(delivered.getNextAttemptAt(), "next_attempt_at must be unset once delivered");

        // A delivered (non-PENDING) delivery is no longer claimable.
        assertNull(service.claimNextDue(new Date(now.getTime() + 60_000)));
    }

    @Test
    void rescheduleOrFailOnEarlyAttemptReschedulesWithFutureBackoff() {
        final Date now = new Date();
        final ObjectId id = savePending("doc-1", new Date(now.getTime() - 1000));

        final long before = System.currentTimeMillis();
        // currentAttempts = 1 -> shortest backoff (30s), still PENDING.
        service.rescheduleOrFail(id, 1, "transient");

        final WebhookDeliveryEntity rescheduled = reload(id);
        assertEquals(WebhookDeliveryEntity.STATUS_PENDING, rescheduled.getStatus());
        assertEquals("transient", rescheduled.getLastError());
        assertNotNull(rescheduled.getNextAttemptAt());

        final long delayMs = rescheduled.getNextAttemptAt().getTime() - before;
        assertTrue(delayMs >= 30_000 - 500 && delayMs <= 30_000 + 5_000,
                "first-attempt backoff should be ~30s, got " + delayMs + "ms");

        // Because it is scheduled into the future, it is not yet due (not-yet branch).
        assertNull(service.claimNextDue(now));
        // But it is claimable once the clock passes the new next_attempt_at (due branch).
        final WebhookDeliveryEntity due = service.claimNextDue(new Date(rescheduled.getNextAttemptAt().getTime() + 1));
        assertNotNull(due);
        assertEquals(id, due.getId());
    }

    @Test
    void rescheduleOrFailAtMaxAttemptsMarksTerminalFailed() {
        final Date now = new Date();
        final ObjectId id = savePending("doc-1", new Date(now.getTime() - 1000));

        service.rescheduleOrFail(id, WebhookDeliveryDataService.MAX_ATTEMPTS, "exhausted");

        final WebhookDeliveryEntity failed = reload(id);
        assertEquals(WebhookDeliveryEntity.STATUS_FAILED, failed.getStatus());
        assertEquals("exhausted", failed.getLastError());
        assertNotNull(failed.getUpdatedAt());
        assertNull(failed.getNextAttemptAt(), "next_attempt_at must be unset on terminal failure");

        // A terminally-failed delivery is no longer claimable.
        assertNull(service.claimNextDue(new Date(now.getTime() + 60_000)));
    }

}
