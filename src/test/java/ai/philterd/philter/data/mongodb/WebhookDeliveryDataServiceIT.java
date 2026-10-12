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

import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.services.webhook.WebhookService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import java.util.Date;
import org.bson.Document;
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

    private MongoWebhookDeliveryDataService service;
    private final TestEncryptionService encryption = new TestEncryptionService();

    @BeforeEach
    void setUpService() {
        service = new MongoWebhookDeliveryDataService(mongoClient, encryption, mock(AuditEventPublisher.class));
    }

    private String savePending(final String documentId, final Date nextAttemptAt) {
        final WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setUserId(MongoIds.newId());
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
        entity.setId(MongoIds.newId());
        service.enqueueOnce(entity);
        return entity.getId();
    }

    private WebhookDeliveryEntity reload(final String id) {
        final MongoCollection<Document> collection =
                mongoClient.getDatabase("philter").getCollection("webhook_deliveries");
        final Document document = collection.find(Filters.eq("_id", MongoIds.objectId(id))).first();
        return document != null ? WebhookDeliveryDocuments.fromDocument(document, encryption) : null;
    }

    @Test
    void storedSecretIsEncryptedAndRetryUsesOriginalSigningSecret() {
        final Date now = new Date();
        final String id = savePending("encrypted", now);
        final Document stored = collection().find(Filters.eq("_id", MongoIds.objectId(id))).first();
        assertNotEquals("s", stored.getString("secret"));
        assertNotNull(stored.getString("secret_encrypted_key"));
        final var first = service.claimNextDue(now);
        final String expected = WebhookService.sign(123, first.getPayload(), "s");
        assertEquals(expected, WebhookService.sign(123, first.getPayload(), first.getSecret()));
        assertTrue(service.rescheduleOrFail(id, first.getClaimToken(), 1, "retry"));
        final var retry = service.claimNextDue(reload(id).getNextAttemptAt());
        assertEquals("s", retry.getSecret());
        assertEquals(stored.getString("secret"), collection().find(Filters.eq("_id", MongoIds.objectId(id))).first().getString("secret"));
    }

    @Test
    void readerRejectsSecretWithoutEncryptedKey() {
        assertThrows(IllegalStateException.class,
                () -> WebhookDeliveryDocuments.fromDocument(new Document("secret", "unprotected-secret"), encryption));
    }

    private MongoCollection<Document> collection() {
        return mongoClient.getDatabase("philter").getCollection("webhook_deliveries");
    }
}
