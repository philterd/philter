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
package ai.philterd.philter.services.signing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.SigningKeyDataService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.junit.jupiter.api.DisplayName;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A hash chain proves only that entries are internally consistent. Anyone with write access to the
 * collection can rewrite an entry and recompute every subsequent hash, producing a chain that
 * validates perfectly. Signing each entry binds it to a key the database does not hold, so a
 * rewritten chain no longer verifies. These tests exercise exactly that attack.
 */
class LedgerSigningIT extends AbstractMongoIT {

    private static final ObjectId USER = new ObjectId();
    private static final String DOC = "doc-signed";

    private SigningKeyDataService signingKeyDataService;
    private SigningService signingService;
    private LedgerDataService ledgerDataService;
    private AdminSettingsDataService adminSettings;

    @BeforeEach
    void setUp() {
        signingKeyDataService = new SigningKeyDataService(mongoClient, new ai.philterd.philter.testutil.TestEncryptionService(), mock(AuditEventPublisher.class));

        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setSigningEnabled(false);
        adminSettings = mock(AdminSettingsDataService.class);
        when(adminSettings.findAdminSettings()).thenReturn(settings);

        signingService = new SigningService(signingKeyDataService, adminSettings);
        ledgerDataService = new LedgerDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), mock(LegalHoldDataService.class), signingService);
    }

    private void writeChain() throws Exception {
        ledgerDataService.initializeLedger(USER, DOC, "input-hash", "file.txt", "default", 1, "policy-hash");
        for (int i = 0; i < 2; i++) {
            final LedgerEntity entry = new LedgerEntity();
            entry.setUserId(USER);
            entry.setDocumentId(DOC);
            entry.setToken("token-" + i);
            entry.setReplacement("{{{REDACTED-ssn}}}");
            entry.setType("ssn");
            entry.setDocumentHash("hash-" + i);
            entry.setPreviousHash(ledgerDataService.getLatestTransaction(USER, DOC).getHash());
            entry.setTimestamp(new java.util.Date());
            entry.setFilename("file.txt");
            entry.setPolicyName("default");
            entry.setPolicyVersion(1);
            entry.setPolicyContentHash("policy-hash");
            entry.setHash(entry.calculateHash());
            ledgerDataService.addTransaction(entry);
        }
    }

    @Test
    void everyEntryIsSignedAndNamesTheKeyThatSignedIt() throws Exception {
        writeChain();

        final List<LedgerEntity> chain = ledgerDataService.getChain(USER, DOC);
        assertEquals(3, chain.size(), "genesis plus two redactions");

        for (final LedgerEntity entry : chain) {
            assertNotNull(entry.getSignature(), "every entry must be signed, including genesis");
            assertEquals(signingKeyDataService.getActiveKeyId(), entry.getSigningKeyId());
            assertTrue(signingService.verifyLedgerEntry(
                    entry.getHash(), entry.getSignature(), entry.getSigningKeyId()));
        }

        assertTrue(ledgerDataService.isChainValid(USER, DOC));
    }

    @Test
    void aRewrittenAndRehashedChainStillFailsVerification() throws Exception {
        writeChain();

        // The attack a hash chain alone cannot detect: change the recorded token, then recompute
        // this entry's hash and relink every entry after it so the chain is internally perfect.
        final List<LedgerEntity> chain = ledgerDataService.getChain(USER, DOC);
        final LedgerEntity target = chain.get(1);
        final String originalHash = target.getHash();
        target.setToken("something-else");
        final String rewrittenHash = target.calculateHash();

        // token is encrypted at rest, so the tampered value has to be written the same way a real
        // attacker with database access would write it.
        final var encrypted = new TestEncryptionService().encrypt("something-else", USER.toHexString());
        mongoClient.getDatabase("philter").getCollection("ledger").updateOne(
                Filters.eq("hash", originalHash),
                Updates.combine(
                        Updates.set("token", encrypted.getEncryptedText()),
                        Updates.set("token_encrypted_key", encrypted.getEncryptionKey()),
                        Updates.set("hash", rewrittenHash)));
        mongoClient.getDatabase("philter").getCollection("ledger").updateOne(
                Filters.eq("previous_hash", originalHash),
                Updates.set("previous_hash", rewrittenHash));

        assertNotEquals(rewrittenHash, originalHash, "the rewrite must change the hash");

        // Without signatures this chain would validate: every hash is correct and every link holds.
        assertFalse(ledgerDataService.isChainValid(USER, DOC),
                "a rewritten chain must fail, because the attacker cannot produce a valid signature");
    }

    @Test
    void entriesStayVerifiableAfterTheSigningKeyIsRotated() throws Exception {
        writeChain();
        final String originalKeyId = signingKeyDataService.getActiveKeyId();

        signingKeyDataService.regenerate("req", USER, null, "source: test");

        assertNotEquals(originalKeyId, signingKeyDataService.getActiveKeyId(), "rotation must change the key");
        assertNotNull(signingKeyDataService.findPublicKeyById(originalKeyId),
                "the superseded key must be retained so historical evidence stays verifiable");
        assertTrue(ledgerDataService.isChainValid(USER, DOC),
                "entries signed with the old key must still verify after rotation");
    }

    @Test
    void validationReportsTheChainAndSignaturesApart() throws Exception {
        writeChain();

        final LedgerDataService.ChainValidation ok = ledgerDataService.validateChain(USER, DOC);
        assertTrue(ok.hashChainValid());
        assertTrue(ok.signaturesValid());
        assertEquals(3, ok.signedEntries());
        assertEquals(0, ok.unsignedEntries());

        // Corrupt only a signature. The hash chain is untouched, so a single boolean would hide
        // which of the two guarantees failed.
        mongoClient.getDatabase("philter").getCollection("ledger").updateOne(
                Filters.eq("previous_hash", LedgerDataService.GENESIS),
                Updates.set("signature", "AAAA"));

        final LedgerDataService.ChainValidation broken = ledgerDataService.validateChain(USER, DOC);
        assertTrue(broken.hashChainValid(), "the hash chain is still intact");
        assertFalse(broken.signaturesValid(), "but the signature no longer verifies");
        assertFalse(broken.valid());
    }

    @Test
    void aSupersededKeyIsStillRetrievableForVerifyingAnExport() throws Exception {
        writeChain();
        final String keyIdUsed = ledgerDataService.getChain(USER, DOC).getFirst().getSigningKeyId();

        signingKeyDataService.regenerate("req", USER, null, "source: test");

        // What an export recipient needs: the PEM of the key named in the entries, even though a
        // different key is now active.
        final String pem = signingKeyDataService.getPublicKeyPem(keyIdUsed);
        assertNotNull(pem, "the key an entry names must remain retrievable after rotation");
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));
        assertNotEquals(keyIdUsed, signingKeyDataService.getActiveKeyId());
    }

    @Test
    void ledgerSignaturesStillVerifyAfterReloadingEncryptedKey() throws Exception {
        writeChain();
        final SigningKeyDataService reloaded = new SigningKeyDataService(mongoClient,
                new ai.philterd.philter.testutil.TestEncryptionService(), mock(AuditEventPublisher.class));
        final SigningService reloadedSigning = new SigningService(reloaded, adminSettings);
        final LedgerDataService reloadedLedger = new LedgerDataService(mongoClient,
                new ai.philterd.philter.testutil.TestEncryptionService(), mock(AuditEventPublisher.class),
                mock(LegalHoldDataService.class), reloadedSigning);
        assertTrue(reloadedLedger.isChainValid(USER, DOC));
    }

    @Test
    void strippedSignaturesInvalidateAnOtherwiseIntactChain() throws Exception {
        writeChain();

        // Removing authentication must not downgrade evidence to an accepted unsigned format.
        mongoClient.getDatabase("philter").getCollection("ledger").updateMany(new Document(),
                Updates.combine(Updates.unset("signature"), Updates.unset("signing_key_id")));

        final var validation = ledgerDataService.validateChain(USER, DOC);
        assertTrue(validation.hashChainValid());
        assertFalse(validation.signaturesValid());
        assertFalse(validation.valid());
        assertEquals(0, validation.signedEntries());
        assertEquals(3, validation.unsignedEntries());
    }


    @Test
    @DisplayName("An entry recorded while the key is rotating still verifies")
    void entriesWrittenDuringARotationStillVerify() throws Exception {

        // The other rotation tests write entries and then rotate. This writes them while rotating,
        // which is the window where the signature and the id it is stamped with could come from
        // different keys — and an entry like that never verifies again, because verification resolves
        // each entry against its own id.
        ledgerDataService.initializeLedger(USER, DOC, "input-hash", "file.txt", "default", 1, "policy-hash");

        final AtomicBoolean writing = new AtomicBoolean(true);
        final List<String> rotationFailures = Collections.synchronizedList(new ArrayList<>());

        final Thread rotator = new Thread(() -> {
            while (writing.get()) {
                try {
                    signingKeyDataService.regenerate("req", null, null, "source: test");
                } catch (final RuntimeException ex) {
                    rotationFailures.add(ex.toString());
                }
            }
        });

        rotator.start();
        try {
            for (int i = 0; i < 60; i++) {
                final LedgerEntity entry = new LedgerEntity();
                entry.setUserId(USER);
                entry.setDocumentId(DOC);
                entry.setToken("token-" + i);
                entry.setReplacement("{{{REDACTED-ssn}}}");
                entry.setType("ssn");
                entry.setDocumentHash("hash-" + i);
                entry.setPreviousHash(ledgerDataService.getLatestTransaction(USER, DOC).getHash());
                entry.setTimestamp(new java.util.Date());
                entry.setFilename("file.txt");
                entry.setPolicyName("default");
                entry.setPolicyVersion(1);
                entry.setPolicyContentHash("policy-hash");
                entry.setHash(entry.calculateHash());
                ledgerDataService.addTransaction(entry);
            }
        } finally {
            writing.set(false);
            rotator.join(60_000);
        }

        assertEquals(List.of(), rotationFailures, "the rotations themselves must succeed");

        final List<LedgerEntity> chain = ledgerDataService.getChain(USER, DOC);
        assertEquals(61, chain.size(), "genesis plus every entry written during the rotations");

        final List<String> unverifiable = new ArrayList<>();
        for (final LedgerEntity entry : chain) {
            if (!signingService.verifyLedgerEntry(entry.getHash(), entry.getSignature(), entry.getSigningKeyId())) {
                unverifiable.add(entry.getSigningKeyId());
            }
        }

        assertEquals(List.of(), unverifiable,
                "every entry must verify against the key its own id names, whenever it was written");
        assertTrue(ledgerDataService.isChainValid(USER, DOC));

    }


    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void strippingOneSignatureRejectsMixedChain(final int index) throws Exception {
        writeChain();
        final LedgerEntity target = ledgerDataService.getChain(USER, DOC).get(index);
        mongoClient.getDatabase("philter").getCollection("ledger").updateOne(
                Filters.eq("_id", target.getId()), Updates.unset("signature"));
        final var result = ledgerDataService.validateChain(USER, DOC);
        assertTrue(result.hashChainValid());
        assertFalse(result.signaturesValid());
        assertFalse(result.valid());
        assertEquals(2, result.signedEntries());
        assertEquals(1, result.unsignedEntries());
    }

    @Test
    void blankSignatureIsCountedAsUnsigned() throws Exception {
        writeChain();
        mongoClient.getDatabase("philter").getCollection("ledger").updateOne(
                Filters.eq("previous_hash", LedgerDataService.GENESIS), Updates.set("signature", "  "));
        final var result = ledgerDataService.validateChain(USER, DOC);
        assertFalse(result.valid());
        assertFalse(result.signaturesValid());
        assertEquals(1, result.unsignedEntries());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown-key"})
    void missingOrUnknownKeyIdInvalidatesSignature(final String keyId) throws Exception {
        writeChain();
        mongoClient.getDatabase("philter").getCollection("ledger").updateOne(
                Filters.eq("previous_hash", LedgerDataService.GENESIS), Updates.set("signing_key_id", keyId));
        final var result = ledgerDataService.validateChain(USER, DOC);
        assertTrue(result.hashChainValid());
        assertFalse(result.signaturesValid());
        assertFalse(result.valid());
    }

    @Test
    void rewrittenRehashedAndStrippedChainCannotPassValidation() throws Exception {
        writeChain();
        String previousHash = LedgerDataService.GENESIS;
        for (final LedgerEntity entry : ledgerDataService.getChain(USER, DOC)) {
            entry.setFilename("forged.txt");
            entry.setPreviousHash(previousHash);
            entry.setHash(entry.calculateHash());
            entry.setSignature(null);
            entry.setSigningKeyId(null);
            mongoClient.getDatabase("philter").getCollection("ledger").replaceOne(
                    Filters.eq("_id", entry.getId()), entry.toDocument(new TestEncryptionService()));
            previousHash = entry.getHash();
        }
        final var result = ledgerDataService.validateChain(USER, DOC);
        assertTrue(result.hashChainValid(), "Attacker recomputed all hashes and links correctly");
        assertFalse(result.signaturesValid());
        assertFalse(result.valid());
    }

    @Test
    void signingFailureRejectsBothInsertionPathsWithoutSavingUnsignedEvidence() throws Exception {
        final SigningService brokenSigner = mock(SigningService.class);
        when(brokenSigner.signLedgerEntry(anyString()))
                .thenThrow(new IllegalStateException("signer unavailable"));
        final var service = new LedgerDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), mock(LegalHoldDataService.class), brokenSigner);
        final LedgerEntity entry = new LedgerEntity(USER, DOC, "", "", 0, "input-hash",
                LedgerDataService.GENESIS, "file", "", "default", 1, "policy-hash");
        // A previously attached signature must not be reused after signing fails.
        entry.setSignature("old-signature");
        entry.setSigningKeyId("old-key");
        assertThrows(IllegalStateException.class, () -> service.addTransaction(entry));
        assertThrows(IllegalStateException.class, () -> service.save(entry));
        assertEquals(0, mongoClient.getDatabase("philter").getCollection("ledger").countDocuments());
    }

    @Test
    void incompleteSigningResultAndMissingHashCannotBeSaved() throws Exception {
        final SigningService brokenSigner = mock(SigningService.class);
        final var service = new LedgerDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), mock(LegalHoldDataService.class), brokenSigner);
        final LedgerEntity entry = new LedgerEntity(USER, DOC, "", "", 0, "input-hash",
                LedgerDataService.GENESIS, "file", "", "default", 1, "policy-hash");
        for (final var result : java.util.Arrays.asList(null,
                new SigningService.LedgerSignature(null, "key"),
                new SigningService.LedgerSignature("", "key"),
                new SigningService.LedgerSignature("signature", null),
                new SigningService.LedgerSignature("signature", ""))) {
            when(brokenSigner.signLedgerEntry(entry.getHash())).thenReturn(result);
            assertThrows(IllegalStateException.class, () -> service.save(entry));
        }
        entry.setHash(null);
        assertThrows(IllegalArgumentException.class, () -> service.save(entry));
        assertEquals(0, mongoClient.getDatabase("philter").getCollection("ledger").countDocuments());
    }

    @Test
    void directSaveSignsAndGenericUpdateCannotRewriteEvidence() throws Exception {
        final LedgerEntity entry = new LedgerEntity(USER, DOC, "", "", 0, "input-hash",
                LedgerDataService.GENESIS, "file", "", "default", 1, "policy-hash");
        assertNotNull(ledgerDataService.save(entry));
        assertTrue(ledgerDataService.isChainValid(USER, DOC));
        final LedgerEntity stored = ledgerDataService.getChain(USER, DOC).getFirst();
        stored.setSignature(null);
        assertThrows(UnsupportedOperationException.class,
                () -> ledgerDataService.update(stored));
        assertTrue(ledgerDataService.isChainValid(USER, DOC));
    }
}
