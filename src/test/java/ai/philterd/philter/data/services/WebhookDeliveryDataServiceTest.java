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
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.result.UpdateResult;
import org.bson.BsonDocument;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.bson.json.JsonWriterSettings;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookDeliveryDataServiceTest {

    @Mock
    private MongoClient mongoClient;

    @Mock
    private MongoDatabase mongoDatabase;

    @Mock
    private MongoCollection<Document> mongoCollection;

    @Mock
    private AuditEventPublisher auditEventPublisher;

    private WebhookDeliveryDataService service;

    @BeforeEach
    void setUp() {
        when(mongoClient.getDatabase("philter")).thenReturn(mongoDatabase);
        when(mongoDatabase.getCollection("webhook_deliveries")).thenReturn(mongoCollection);
        service = new WebhookDeliveryDataService(mongoClient, auditEventPublisher);
    }

    @Test
    void claimNextDueReturnsNullWhenEmpty() {
        when(mongoCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(null);

        assertNull(service.claimNextDue(new Date()));
    }

    @Test
    void claimNextDueReturnsEntity() {
        final ObjectId id = new ObjectId();
        final Document claimed = new Document("_id", id)
                .append("user_id", new ObjectId())
                .append("document_id", "d")
                .append("event_type", WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE)
                .append("status", WebhookDeliveryEntity.STATUS_PENDING)
                .append("url", "https://e.com")
                .append("secret", "s")
                .append("payload", "{}")
                .append("attempts", 1);

        when(mongoCollection.findOneAndUpdate(any(Bson.class), any(Bson.class), any(FindOneAndUpdateOptions.class)))
                .thenReturn(claimed);

        final WebhookDeliveryEntity entity = service.claimNextDue(new Date());
        assertNotNull(entity);
        assertEquals(id, entity.getId());
        assertEquals(1, entity.getAttempts());
    }

    @Test
    void markDeliveredSetsStatusDelivered() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));
        service.markDelivered(new ObjectId());
        verify(mongoCollection).updateOne(any(Bson.class), any(Bson.class));
    }

    @Test
    void rescheduleOnEarlyAttemptUsesShortestBackoff() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        final long beforeMs = System.currentTimeMillis();
        service.rescheduleOrFail(new ObjectId(), 1, "boom");

        final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), updateCaptor.capture());

        final Date nextAttempt = extractDate(updateCaptor.getValue(), "next_attempt_at");
        assertNotNull(nextAttempt, "rescheduled update must set next_attempt_at");

        final long delayMs = nextAttempt.getTime() - beforeMs;
        // First attempt: 30s backoff. Allow generous bound for test execution time.
        assertTrue(delayMs >= 30_000 - 500 && delayMs <= 30_000 + 5_000,
                "first-attempt backoff should be ~30s, got " + delayMs + "ms");
    }

    @Test
    void rescheduleAtMaxAttemptsMarksTerminalFailed() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        service.rescheduleOrFail(new ObjectId(), WebhookDeliveryDataService.MAX_ATTEMPTS, "exhausted");

        final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
        verify(mongoCollection).updateOne(any(Bson.class), updateCaptor.capture());

        final String renderedUpdate = render(updateCaptor.getValue());
        assertTrue(renderedUpdate.contains("\"status\": \"" + WebhookDeliveryEntity.STATUS_FAILED + "\""),
                "Terminal failure must set status to FAILED, got: " + renderedUpdate);
        assertTrue(renderedUpdate.contains("\"last_error\": \"exhausted\""),
                "Terminal failure must record last_error, got: " + renderedUpdate);
    }

    @Test
    void backoffSchedulesAreNonDecreasing() {
        when(mongoCollection.updateOne(any(Bson.class), any(Bson.class))).thenReturn(mock(UpdateResult.class));

        Date previous = null;
        for (int attempt = 1; attempt < WebhookDeliveryDataService.MAX_ATTEMPTS; attempt++) {
            final ArgumentCaptor<Bson> updateCaptor = ArgumentCaptor.forClass(Bson.class);
            final long beforeMs = System.currentTimeMillis();
            service.rescheduleOrFail(new ObjectId(), attempt, "retry");
            verify(mongoCollection, org.mockito.Mockito.atLeastOnce()).updateOne(any(Bson.class), updateCaptor.capture());

            final Date next = extractDate(updateCaptor.getValue(), "next_attempt_at");
            assertNotNull(next, "attempt " + attempt + " should set next_attempt_at");
            final long delayMs = next.getTime() - beforeMs;
            assertTrue(delayMs >= 0, "attempt " + attempt + " backoff must not be negative");

            if (previous != null) {
                assertTrue(!next.before(previous),
                        "attempt " + attempt + " backoff must not decrease (prev=" + previous + ", now=" + next + ")");
            }
            previous = next;
        }
    }

    private static Date extractDate(final Bson update, final String fieldName) {
        final BsonDocument doc = update.toBsonDocument(BsonDocument.class, defaultRegistry());
        final BsonDocument set = doc.containsKey("$set") ? doc.getDocument("$set") : doc;
        if (!set.containsKey(fieldName)) {
            return null;
        }
        return new Date(set.getDateTime(fieldName).getValue());
    }

    private static String render(final Bson update) {
        return update.toBsonDocument(BsonDocument.class, defaultRegistry())
                .toJson(JsonWriterSettings.builder().indent(false).build());
    }

    private static CodecRegistry defaultRegistry() {
        return CodecRegistries.fromProviders(new org.bson.codecs.BsonValueCodecProvider(),
                new org.bson.codecs.ValueCodecProvider(),
                new org.bson.codecs.DocumentCodecProvider());
    }

}
