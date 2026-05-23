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
package ai.philterd.philter.data.entities;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WebhookDeliveryEntityTest {

    @Test
    void roundTripPreservesAllFields() {
        final ObjectId id = new ObjectId();
        final ObjectId userId = new ObjectId();
        final Date created = new Date(1_700_000_000_000L);
        final Date updated = new Date(1_700_000_001_000L);
        final Date next = new Date(1_700_000_002_000L);
        final Date delivered = new Date(1_700_000_003_000L);

        final WebhookDeliveryEntity original = new WebhookDeliveryEntity();
        original.setId(id);
        original.setUserId(userId);
        original.setDocumentId("doc-1");
        original.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
        original.setStatus(WebhookDeliveryEntity.STATUS_DELIVERED);
        original.setUrl("https://example.com/hook");
        original.setSecret("super-secret-value-1234");
        original.setPayload("{\"event\":\"x\"}");
        original.setAttempts(3);
        original.setLastError("HTTP 500");
        original.setNextAttemptAt(next);
        original.setCreatedAt(created);
        original.setUpdatedAt(updated);
        original.setDeliveredAt(delivered);

        final Document doc = original.toDocument();
        final WebhookDeliveryEntity restored = WebhookDeliveryEntity.fromDocument(doc);

        assertEquals(id, restored.getId());
        assertEquals(userId, restored.getUserId());
        assertEquals("doc-1", restored.getDocumentId());
        assertEquals(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE, restored.getEventType());
        assertEquals(WebhookDeliveryEntity.STATUS_DELIVERED, restored.getStatus());
        assertEquals("https://example.com/hook", restored.getUrl());
        assertEquals("super-secret-value-1234", restored.getSecret());
        assertEquals("{\"event\":\"x\"}", restored.getPayload());
        assertEquals(3, restored.getAttempts());
        assertEquals("HTTP 500", restored.getLastError());
        assertEquals(next, restored.getNextAttemptAt());
        assertEquals(created, restored.getCreatedAt());
        assertEquals(updated, restored.getUpdatedAt());
        assertEquals(delivered, restored.getDeliveredAt());
    }

    @Test
    void toDocumentOmitsIdWhenNull() {
        final WebhookDeliveryEntity entity = new WebhookDeliveryEntity();
        entity.setStatus(WebhookDeliveryEntity.STATUS_PENDING);

        final Document doc = entity.toDocument();

        assertNull(doc.get("_id"));
    }

    @Test
    void attemptsDefaultToZero() {
        final Document doc = new Document();
        final WebhookDeliveryEntity restored = WebhookDeliveryEntity.fromDocument(doc);
        assertEquals(0, restored.getAttempts());
    }

}
