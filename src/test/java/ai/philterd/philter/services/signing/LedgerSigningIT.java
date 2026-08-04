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

    @BeforeEach
    void setUp() {
        signingKeyDataService = new SigningKeyDataService(mongoClient, mock(AuditEventPublisher.class));

        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setSigningEnabled(false);
        final AdminSettingsDataService adminSettings = mock(AdminSettingsDataService.class);
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

        signingKeyDataService.regenerate(USER);

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

        signingKeyDataService.regenerate(USER);

        // What an export recipient needs: the PEM of the key named in the entries, even though a
        // different key is now active.
        final String pem = signingKeyDataService.getPublicKeyPem(keyIdUsed);
        assertNotNull(pem, "the key an entry names must remain retrievable after rotation");
        assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----"));
        assertNotEquals(keyIdUsed, signingKeyDataService.getActiveKeyId());
    }

    @Test
    void unsignedLegacyEntriesDoNotFailValidation() throws Exception {
        writeChain();

        // Entries written before signing existed carry no signature and cannot be signed after the
        // fact. Treating them as tampered would misreport existing deployments' history.
        mongoClient.getDatabase("philter").getCollection("ledger").updateMany(new Document(),
                Updates.combine(Updates.unset("signature"), Updates.unset("signing_key_id")));

        assertTrue(ledgerDataService.isChainValid(USER, DOC),
                "an unsigned chain is unproven, not invalid");
    }

}
