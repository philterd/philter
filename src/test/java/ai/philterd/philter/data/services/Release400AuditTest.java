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
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Audit characterizations: a pass demonstrates existing defects, except the A04 queued-secret, A05 signature-stripping, A06 exclusive-claim, A09 policy-tweak, A10 expiry, A12 whole-chain retention, and A11 shared signing-key
 * tests, which have been converted to regression tests.
 * Convert these assertions to regression expectations when implementing the corresponding fixes.
 * Uses the repository's MongoDB protocol emulator, not a production MongoDB server.
 */
class Release400AuditTest extends AbstractMongoIT {
    private final AuditEventPublisher audit = mock(AuditEventPublisher.class);

    @Test
    void staleAccountPasswordSavePreservesRevokedRoleAndDeactivation() {
        final UserService service = new UserService(mongoClient, new TestEncryptionService(), audit);
        final UserEntity user = new UserEntity();
        user.setUsername("audit-user");
        user.setRole("admin");
        user.setPassword("unused");
        final ObjectId id = service.save(user);
        final UserEntity staleForm = service.findOneById(id);
        final UserEntity adminCopy = service.findOneById(id);
        service.setUserRole("audit", adminCopy, "user", "test");
        service.deactivateUser("audit", adminCopy, "test");
        assertTrue(service.isDeactivated(id));
        service.changePassword("audit", staleForm, "a-new-audit-password", "test");
        assertTrue(service.isDeactivated(id), "Password changes must preserve deactivation");
        assertEquals("user", service.findOneById(id).getRole(), "Password changes must preserve demotion");
    }

    @Test
    void webhookCannotBeClaimedAgainBeforeFirstDeliveryFinishes() {
        final WebhookDeliveryDataService service = new WebhookDeliveryDataService(mongoClient, new ai.philterd.philter.testutil.TestEncryptionService(), audit);
        final ObjectId id = new ObjectId();
        mongoClient.getDatabase("philter").getCollection("webhook_deliveries").insertOne(
                new Document("_id", id).append("user_id", new ObjectId())
                        .append("status", "PENDING").append("attempts", 0)
                        .append("next_attempt_at", new Date(0)));
        final var first = service.claimNextDue(new Date());
        final var second = service.claimNextDue(new Date());
        assertNotNull(first);
        assertNull(second, "An active delivery claim must exclude other workers");
        assertEquals(1, first.getAttempts());
    }

    @Test
    void oldWorkerCannotOverwriteCompletedJobWithFailure() {
        final PendingDocumentDataService service = new PendingDocumentDataService(
                mongoClient, new TestEncryptionService(), audit);
        final ObjectId id = new ObjectId();
        final var collection = mongoClient.getDatabase("philter").getCollection("pending_documents");
        collection.insertOne(new Document("_id", id).append("user_id", new ObjectId())
                .append("status", "COMPLETE").append("claimed_by", "new-worker"));
        assertFalse(service.markFailed(id, "attempt", "late failure from old worker"));
        assertEquals("COMPLETE", collection.find(new Document("_id", id)).first().getString("status"));
    }

    @Test
    void conflictingPolicyRevisionIsRejectedAndOriginalRemainsResolvable() {
        final PolicyVersionDataService service = new PolicyVersionDataService(mongoClient, audit);
        final PolicyEntity policy = new PolicyEntity();
        policy.setUserId(new ObjectId());
        policy.setName("audit-policy");
        policy.setRevision(1);
        policy.setPolicy("{\"name\":\"original\"}");
        final String oldHash = service.snapshot(policy);
        policy.setPolicy("{\"name\":\"recreated\"}");
        assertThrows(IllegalStateException.class, () -> service.snapshot(policy));
        assertNotNull(service.findByContentHash(oldHash));
    }

    @Test
    void agePurgePreservesWholeChainAcrossCutoff() {
        final LedgerDataService service = new LedgerDataService(mongoClient, new TestEncryptionService(),
                audit, new LegalHoldDataService(mongoClient, audit), mock(ai.philterd.philter.services.signing.SigningService.class));
        final ObjectId userId = new ObjectId();
        final var collection = mongoClient.getDatabase("philter").getCollection("ledger");
        collection.insertOne(new Document("user_id", userId).append("document_id", "one-chain")
                .append("previous_hash", LedgerDataService.GENESIS).append("timestamp", new Date(0)));
        collection.insertOne(new Document("user_id", userId).append("document_id", "one-chain")
                .append("previous_hash", "genesis-hash").append("timestamp", new Date()));
        mongoClient.getDatabase("philter").getCollection("ledger_chains").insertOne(
                new Document("_id", userId + ":one-chain").append("user_id", userId)
                        .append("document_id", "one-chain").append("state", "complete")
                        .append("completed_at", new Date(0)).append("latest_entry_at", new Date()));
        assertTrue(service.deleteChainsByUserIdAndOlderThan("audit", userId, 1).isSuccessful());
        assertEquals(2, collection.countDocuments(new Document("document_id", "one-chain")));
        assertEquals(1, collection.countDocuments(new Document("previous_hash", LedgerDataService.GENESIS)));
    }

    @Test
    void failedWebhookReceivesTerminalRetentionTimestamp() {
        final WebhookDeliveryDataService service = new WebhookDeliveryDataService(mongoClient, new ai.philterd.philter.testutil.TestEncryptionService(), audit);
        final ObjectId id = new ObjectId();
        final var collection = mongoClient.getDatabase("philter").getCollection("webhook_deliveries");
        collection.insertOne(new Document("_id", id).append("status", "PROCESSING")
                .append("claim_token", "claim").append("claim_expires_at", new Date(System.currentTimeMillis() + 60_000))
                .append("attempts", WebhookDeliveryDataService.MAX_ATTEMPTS));
        service.rescheduleOrFail(id, "claim", WebhookDeliveryDataService.MAX_ATTEMPTS, "unreachable");
        final Document failed = collection.find(new Document("_id", id)).first();
        assertEquals("FAILED", failed.getString("status"));
        assertNotNull(failed.get("completed_at"), "Failed deliveries must qualify for retention");
    }

    @Test
    void removingSignatureFromSignedLedgerProducesInvalidResult() throws Exception {
        final var signing = mock(ai.philterd.philter.services.signing.SigningService.class);
        org.mockito.Mockito.when(signing.signLedgerEntry(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new ai.philterd.philter.services.signing.SigningService.LedgerSignature("signature", "key"));
        final LedgerDataService service = new LedgerDataService(mongoClient, new TestEncryptionService(),
                audit, new LegalHoldDataService(mongoClient, audit), signing);
        final ObjectId userId = new ObjectId();
        service.initializeLedger(userId, "signed-chain", "input-hash", "file", "policy", 1, "policy-hash");
        final var collection = mongoClient.getDatabase("philter").getCollection("ledger");
        collection.updateMany(new Document(), new Document("$unset", new Document("signature", "")));
        final var result = service.validateChain(userId, "signed-chain");
        assertFalse(result.valid(), "Removing a signature must invalidate the evidence");
        assertFalse(result.signaturesValid());
        assertEquals(1, result.unsignedEntries());
        org.mockito.Mockito.verify(signing, org.mockito.Mockito.never()).verifyLedgerEntry(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rotatingSigningKeyRefreshesAnotherInstancesActiveKey() {
        final var encryption = new TestEncryptionService();
        final var first = new SigningKeyDataService(mongoClient, encryption, audit);
        final var second = new SigningKeyDataService(mongoClient, encryption, audit);
        assertEquals(first.getActiveKeyId(), second.getActiveKeyId());
        first.regenerate("req", new ObjectId(), null, "source: test");
        assertEquals(first.getActiveKeyId(), second.getActiveKeyId(),
                "The second instance must select the newly published key");
    }

    @Test
    void queuedWebhookEncryptsSigningSecret() {
        final var service = new WebhookDeliveryDataService(mongoClient, new ai.philterd.philter.testutil.TestEncryptionService(), audit);
        final var delivery = new ai.philterd.philter.data.entities.WebhookDeliveryEntity();
        delivery.setUserId(new ObjectId());
        delivery.setSecret("audit-only-webhook-secret");
        final ObjectId id = service.save(delivery);
        final Document stored = mongoClient.getDatabase("philter").getCollection("webhook_deliveries")
                .find(new Document("_id", id)).first();
        assertNotEquals("audit-only-webhook-secret", stored.getString("secret"));
        assertNotNull(stored.getString("secret_encrypted_key"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reidentifyPreservesExplicitPolicyFpeTweak() throws Exception {
        final String key = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";
        final String plaintext = "1234567890";
        final var fpe = new ai.philterd.phileas.policy.FPE(key, "1234567890abcdef");
        final String encrypted = ai.philterd.phileas.utils.Encryption.formatPreservingEncrypt(fpe, plaintext);
        final var users = mock(UserService.class);
        final var policies = mock(PolicyDataService.class);
        final ObjectId userId = new ObjectId();
        org.mockito.Mockito.when(users.findOneById(userId)).thenReturn(new UserEntity());
        final var policy = new PolicyEntity();
        policy.setPolicy(new com.google.gson.Gson().toJson(java.util.Map.of("fpe", fpe)));
        org.mockito.Mockito.when(policies.findOne("policy", userId)).thenReturn(policy);
        final var controller = new ai.philterd.philter.api.controllers.ReidentifyApiController(users, policies,
                mock(ApiKeyDataService.class), audit, mock(ai.philterd.philter.services.cache.ApiKeyCache.class),
                new com.google.gson.Gson());
        final var request = new ai.philterd.philter.api.requests.ReidentifyRequest();
        request.setPolicyName("policy");
        request.setValues(java.util.List.of(encrypted));
        final var method = controller.getClass().getDeclaredMethod("reidentifyFpe", request.getClass(), ObjectId.class);
        method.setAccessible(true);
        final var results = (java.util.List<ai.philterd.philter.api.responses.ReidentifyResponse.ReidentifyResult>)
                method.invoke(controller, request, userId);
        assertNull(results.getFirst().getError());
        assertEquals(plaintext, results.getFirst().getDecrypted());
    }

    @Test
    void expiredInMemoryCacheEntriesAreReclaimedWithoutAnotherRead() throws Exception {
        final var backend = ai.philterd.philter.services.cache.InMemoryCacheBackend.INSTANCE;
        final String key = "audit-expiry-" + new ObjectId();
        backend.setex(key, 1, "1");
        final var field = backend.getClass().getDeclaredField("strings");
        field.setAccessible(true);
        final var entries = (java.util.Map<?, ?>) field.get(backend);
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (entries.containsKey(key) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(entries.containsKey(key), "Expired entries must be reclaimed without a cache read");
    }
}
