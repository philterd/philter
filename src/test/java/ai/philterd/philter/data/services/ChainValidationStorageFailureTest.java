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
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.services.signing.SigningService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * A storage failure while validating a ledger chain says nothing about the chain, so it is thrown rather than
 * reported as an invalid or unverifiable chain, which would read as tampering.
 */
class ChainValidationStorageFailureTest {

    private static StoredLedgerDataService ledger(final SigningService signingService) {
        return mock(StoredLedgerDataService.class, withSettings()
                .useConstructor(mock(AuditEventPublisher.class), mock(LegalHoldDataService.class), signingService,
                        mock(EvidenceGuard.class))
                .defaultAnswer(CALLS_REAL_METHODS));
    }

    private static LedgerEntity signedGenesis() throws Exception {
        final LedgerEntity genesis = new LedgerEntity("user", "doc", "", "", 0, "document-hash",
                LedgerDataService.GENESIS, "file.txt", "", "default", 1, "policy-hash");
        genesis.setSignature("signature");
        genesis.setSigningKeyId("superseded-key");
        return genesis;
    }

    @Test
    @DisplayName("A storage failure while finding a superseded signing key is thrown, not reported as a bad signature")
    void aStorageFailureFindingAKeyIsThrown() {
        final StoredSigningKeyDataService signingKeys = mock(StoredSigningKeyDataService.class, withSettings()
                .useConstructor(mock(AuditEventPublisher.class), null)
                .defaultAnswer(CALLS_REAL_METHODS));
        doThrow(new IllegalStateException("storage failed")).when(signingKeys).findPublicKey("superseded-key");

        assertThrows(IllegalStateException.class, () -> signingKeys.findPublicKeyById("superseded-key"));
        assertThrows(IllegalStateException.class, () -> new SigningService(signingKeys, null)
                .verifyLedgerEntry("hash", "signature", "superseded-key"));
    }

    @Test
    @DisplayName("A storage failure while checking a chain's signatures is thrown, not reported as an invalid chain")
    void aStorageFailureCheckingSignaturesIsThrown() throws Exception {
        final SigningService signingService = mock(SigningService.class);
        when(signingService.verifyLedgerEntry(any(), any(), any())).thenThrow(new IllegalStateException("storage failed"));
        final StoredLedgerDataService ledger = ledger(signingService);
        doReturn(List.of(signedGenesis())).when(ledger).getChain("user", "doc");

        assertThrows(IllegalStateException.class, () -> ledger.validateChain("user", "doc"));
    }

    @Test
    @DisplayName("A signature that does not verify still reports the chain's signatures as invalid")
    void aBadSignatureIsStillReported() throws Exception {
        final SigningService signingService = mock(SigningService.class);
        when(signingService.verifyLedgerEntry(any(), any(), any())).thenReturn(false);
        final StoredLedgerDataService ledger = ledger(signingService);
        doReturn(List.of(signedGenesis())).when(ledger).getChain("user", "doc");

        final LedgerDataService.ChainValidation validation = ledger.validateChain("user", "doc");
        assertFalse(validation.signaturesValid());
        assertEquals(1, validation.signedEntries());
    }

}
