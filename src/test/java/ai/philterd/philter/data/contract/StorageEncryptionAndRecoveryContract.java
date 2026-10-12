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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarantees that span services: secrets are stored encrypted, a ledger entry that can no longer be read is still
 * listed, and the evidence guard serializes legal hold changes with ledger deletions.
 */
public interface StorageEncryptionAndRecoveryContract extends StorageContract {

    private UserEntity user(final String username, final String role) {
        assertTrue(storage().users().createUser("req", username, null, role, storage().policies(), storage().contexts(),
                "test").isSuccessful());
        return storage().users().findByUsername(username);
    }

    private void completeChain(final String owner, final String documentId, final String token) throws Exception {
        final LedgerDataService ledger = storage().ledger();
        ledger.initializeLedger(owner, documentId, "input-hash", "file.pdf", "policy", 0, "policy-hash");
        ledger.addTransaction(new LedgerEntity(owner, documentId, token, "{{{REDACTED-ssn}}}", 0, "input-hash",
                ledger.getLatestTransaction(owner, documentId).getHash(), "file.pdf", "ssn", "policy", 0, "policy-hash"));
        ledger.completeChain(owner, documentId);
    }

    @Test
    default void secretsAreStoredEncrypted() throws Exception {

        final UserEntity admin = user("admin", UserService.ROLE_ADMIN);
        final UserEntity user = user("jordan", UserService.ROLE_USER);
        final String owner = user.getId();

        // Each secret is distinctive, so finding it anywhere in storage means it was written in the clear.
        final String fpeKey = storage().users().ensureFpeKey(user);
        final String mfaSecret = storage().users().startMfaEnrollment(user);
        final String webhookSecret = "webhook-secret-" + RecordIds.newId();
        assertTrue(storage().users().setWebhook("req", user, "https://93.184.215.14/hook", webhookSecret, null, "test",
                null, null).isSuccessful());
        final String phieldKey = "phield-key-" + RecordIds.newId();
        storage().adminSettings().update(new AdminSettingsDataService.Update(null, null, null, null, null, null, null,
                phieldKey, null, null), admin.getId(), null);
        final String listItem = "list-item-" + RecordIds.newId();
        assertTrue(storage().customLists().saveOrUpdate("req", owner, "names", null, List.of(listItem), false, "test")
                .isSuccessful());
        final String redactTerm = "redact-term-" + RecordIds.newId();
        storage().redactLists().saveOrUpdate("req", owner, List.of(redactTerm), List.of(), "test");
        final String ledgerToken = "ledger-token-" + RecordIds.newId();
        completeChain(owner, "doc", ledgerToken);
        final String documentText = "document-text-" + RecordIds.newId();
        final PendingDocumentEntity job = new PendingDocumentEntity();
        job.setUserId(owner);
        job.setDocumentId(RecordIds.newId());
        job.setStatus(PendingDocumentEntity.STATUS_PENDING);
        job.setSubmittedAt(new Date());
        job.setInput(documentText.getBytes(StandardCharsets.UTF_8));
        storage().pendingDocuments().save(job);
        final String deliverySecret = "delivery-secret-" + RecordIds.newId();
        final WebhookDeliveryEntity delivery = new WebhookDeliveryEntity();
        delivery.setId(RecordIds.newId());
        delivery.setUserId(owner);
        delivery.setDocumentId(job.getDocumentId());
        delivery.setEventType(WebhookDeliveryEntity.EVENT_DOCUMENT_REDACTION_COMPLETE);
        delivery.setStatus(WebhookDeliveryEntity.STATUS_PENDING);
        delivery.setUrl("https://example.com/hook");
        delivery.setSecret(deliverySecret);
        delivery.setPayload("{}");
        delivery.setNextAttemptAt(new Date());
        delivery.setCreatedAt(new Date());
        delivery.setUpdatedAt(new Date());
        storage().webhookDeliveries().enqueueOnce(delivery);

        for (final String secret : List.of(fpeKey, mfaSecret, webhookSecret, phieldKey, listItem, redactTerm, ledgerToken,
                documentText, deliverySecret)) {
            assertFalse(storage().storageContains(secret), "stored in the clear: " + secret);
        }

        // The hook does find what is stored as it is, so the checks above are not vacuous.
        assertTrue(storage().storageContains("jordan"));

        // And everything still reads back.
        final UserEntity stored = storage().anotherInstance().users().findOneById(owner);
        assertEquals(fpeKey, stored.getFpeKey());
        assertEquals(mfaSecret, stored.getMfaPendingSecret());
        assertEquals(webhookSecret, stored.getWebhookSecret());
        assertEquals(phieldKey, storage().anotherInstance().adminSettings().findAdminSettings().getPhieldApiKey());
        assertEquals(ledgerToken, storage().ledger().getChain(owner, "doc").get(1).getToken());
    }

    @Test
    default void anUnreadableLedgerEntryIsListedMarkedUnreadable() throws Exception {
        final String owner = RecordIds.newId();
        completeChain(owner, "damaged", "123-45-6789");
        completeChain(owner, "intact", "987-65-4321");
        storage().damageLedgerChain(owner, "damaged");

        final Listings.Page<LedgerEntity> chains = storage().ledger().listChains("req", owner, null,
                new Listings.Sort("timestamp", true), 0, 10, "test");
        assertEquals(2, chains.total(), "the damaged chain is still listed");
        for (final LedgerEntity head : chains.items()) {
            assertEquals("damaged".equals(head.getDocumentId()), head.isUnreadable(), head.getDocumentId());
            assertNotNull(head.getHash(), "the fields stored in the clear are read");
        }

        final LedgerDataService.ChainValidation damaged = storage().ledger().validateChain(owner, "damaged");
        assertFalse(damaged.valid());
        assertEquals(LedgerDataService.CHAIN_UNVERIFIABLE, damaged.error());
        assertTrue(storage().ledger().validateChain(owner, "intact").hashChainValid());
    }

    @Test
    default void aHeldEvidenceGuardRefusesHoldChangesAndDeletions() throws Exception {
        final String owner = RecordIds.newId();
        completeChain(owner, "doc", "123-45-6789");

        // As an interrupted operation leaves it: held until an operator recovers it.
        final String token = storage().evidenceGuard().acquire(owner, "interrupted");
        assertNotNull(token);
        assertEquals(null, storage().anotherInstance().evidenceGuard().acquire(owner, "other"), "held across instances");

        final ServiceResponse hold = storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_USER, "jordan",
                "litigation", owner, owner);
        assertEquals(409, hold.getStatusCode());
        assertEquals(LegalHoldDataService.REASON_OPERATION_IN_PROGRESS, hold.getDetails());
        final ServiceResponse deletion = storage().ledger().deleteByDocumentId("req", owner, "doc", "test");
        assertEquals(409, deletion.getStatusCode());
        assertTrue(storage().ledger().chainExists(owner, "doc"));

        // Another owner is not affected.
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_USER, "riley",
                "litigation", RecordIds.newId(), owner).getStatusCode());

        assertTrue(storage().evidenceGuard().release(owner, token));
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_USER, "jordan",
                "litigation", owner, owner).getStatusCode());
    }

    @Test
    default void anEvidenceOperationThatFailsPartWayLeavesItsGuardHeld() {
        final String owner = RecordIds.newId();
        assertThrows(IllegalStateException.class, () -> storage().evidenceGuard().execute(owner, "uncertain", () -> {
            throw new IllegalStateException("the storage may still be deleting");
        }));
        // A restarted or other instance still sees it held, so no hold or deletion can race the failed operation.
        final ServiceResponse hold = storage().anotherInstance().legalHolds().create("req", "case-1",
                LegalHoldEntity.SCOPE_USER, owner, "litigation", owner, owner);
        assertEquals(409, hold.getStatusCode());
        assertEquals(LegalHoldDataService.REASON_OPERATION_IN_PROGRESS, hold.getDetails());
        assertEquals(409, storage().anotherInstance().ledger().deleteChainsByUserIdAndOlderThan("req", owner, 0)
                .getStatusCode());
    }

}
