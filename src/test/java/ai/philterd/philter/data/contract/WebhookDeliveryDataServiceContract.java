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

import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link WebhookDeliveryDataService}. */
public interface WebhookDeliveryDataServiceContract extends StorageContract {

    private WebhookDeliveryEntity delivery(final String owner, final String documentId, final Date createdAt) {
        return delivery(RecordIds.newId(), owner, documentId, createdAt);
    }

    /** A delivery whose identity is {@code id}: enqueuing the same identity again is the same delivery. */
    private WebhookDeliveryEntity delivery(final String id, final String owner, final String documentId,
            final Date createdAt) {
        final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setId(id);
        delivery.setUserId(owner);
        delivery.setDocumentId(documentId);
        delivery.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
        delivery.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
        delivery.setUrl("https://example.com/hook");
        delivery.setSecret("a secret, \"quoted\", Ünïcödé");
        delivery.setPayload("{}");
        delivery.setAttempts(0);
        delivery.setNextAttemptAt(createdAt);
        delivery.setCreatedAt(createdAt);
        delivery.setUpdatedAt(createdAt);
        return delivery;
    }

    @Test
    default void webhookDeliveryConcurrentWorkersClaimADeliveryOnceAndCountTheAttempt() throws Exception {
        final String owner = RecordIds.newId();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", new Date()));
        final Date now = new Date();
        final List<WebhookDeliveryEntity> claims = StorageContract.concurrently(8,
                () -> storage().anotherInstance().webhookDeliveries().claimNextDue(now));
        final List<WebhookDeliveryEntity> won = claims.stream().filter(Objects::nonNull).toList();
        assertEquals(1, won.size());
        assertEquals(1, won.get(0).getAttempts());
        assertEquals("a secret, \"quoted\", Ünïcödé", won.get(0).getSecret());
    }

    @Test
    default void webhookDeliveryAStaleClaimCannotFinishTheDelivery() {
        final String owner = RecordIds.newId();
        storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc", new Date()));
        final WebhookDeliveryEntity claim = storage().webhookDeliveries().claimNextDue(new Date());
        assertNotNull(claim);
        assertFalse(storage().webhookDeliveries().markDelivered(claim.getId(), "another-token"));
        assertTrue(storage().webhookDeliveries().markDelivered(claim.getId(), claim.getClaimToken()));
        assertFalse(storage().webhookDeliveries().markDelivered(claim.getId(), claim.getClaimToken()));
        assertNull(storage().webhookDeliveries().claimNextDue(new Date(System.currentTimeMillis() + 86_400_000L)));
    }

    @Test
    default void webhookDeliveryEnqueuingTheSameEventTwiceDeliversItOnce() throws Exception {
        final String owner = RecordIds.newId();
        final String id = RecordIds.newId();
        StorageContract.concurrently(4, () -> {
            storage().anotherInstance().webhookDeliveries().enqueueOnce(delivery(id, owner, "doc", new Date()));
            return null;
        });
        assertEquals(1, storage().webhookDeliveries().countByUserId(owner));
    }

    @Test
    default void webhookDeliveryListingsOrderByCreationTime() {
        final String owner = RecordIds.newId();
        final long start = System.currentTimeMillis() - 60_000;
        for (final int i : List.of(2, 0, 4, 1, 3)) {
            storage().webhookDeliveries().enqueueOnce(delivery(owner, "doc-" + i, new Date(start + i * 1000L)));
        }
        assertEquals(5, storage().webhookDeliveries().countByUserId(owner));
        final List<String> ascending = new java.util.ArrayList<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().webhookDeliveries().findByUserId(owner, offset, 2, false)
                    .forEach(delivery -> ascending.add(delivery.getDocumentId()));
        }
        assertEquals(List.of("doc-0", "doc-1", "doc-2", "doc-3", "doc-4"), ascending);
        assertEquals("doc-4", storage().webhookDeliveries().findByUserId(owner, 0, 1, true).get(0).getDocumentId());
    }

}
