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

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the redaction-ledger entry's hash chain, including the governing-policy stamp added
 * for court-grade evidence. These verify, without a database, that a per-redaction entry forms a valid
 * link (its hash recomputes and chains to the previous entry) and that the policy version is part of
 * the tamper-evident hash.
 */
class LedgerEntityTest {

    /** Builds a per-redaction entry the way RedactionService does: set fields, then compute the hash. */
    private static LedgerEntity entry(final ObjectId userId, final String documentId, final String token,
                                      final String previousHash, final String policyName, final int policyVersion,
                                      final String policyContentHash) throws Exception {
        final LedgerEntity entity = new LedgerEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setToken(token);
        entity.setReplacement("{{{REDACTED}}}");
        entity.setStartPosition(0);
        entity.setDocumentHash("dochash");
        entity.setTimestamp(new Date(0));
        entity.setPreviousHash(previousHash);
        entity.setType("PERSON");
        entity.setPolicyName(policyName);
        entity.setPolicyVersion(policyVersion);
        entity.setPolicyContentHash(policyContentHash);
        entity.setHash(entity.calculateHash());
        return entity;
    }

    @Test
    void perRedactionEntryFormsAValidChainLink() throws Exception {
        final ObjectId userId = new ObjectId();

        // The genesis entry carries the governing policy too.
        final LedgerEntity genesis = new LedgerEntity(userId, "doc-1", "", "", 0, "inputhash", "[genesis]",
                "file", "", "default", 5, "policyhash");

        final LedgerEntity redaction = entry(userId, "doc-1", "John Smith", genesis.getHash(), "default", 5, "policyhash");

        // The stored hash recomputes (entry not tampered) and links to the previous entry, which is
        // exactly what LedgerDataService.isChainValid checks.
        assertEquals(redaction.getHash(), redaction.calculateHash());
        assertEquals(genesis.getHash(), redaction.getPreviousHash());
        assertEquals(genesis.getHash(), genesis.calculateHash());
    }

    @Test
    void policyVersionIsPartOfTheTamperEvidentHash() throws Exception {
        final ObjectId userId = new ObjectId();

        final LedgerEntity v5 = entry(userId, "doc-1", "John Smith", "prev", "default", 5, "hashA");
        final LedgerEntity v6 = entry(userId, "doc-1", "John Smith", "prev", "default", 6, "hashA");
        final LedgerEntity differentHash = entry(userId, "doc-1", "John Smith", "prev", "default", 5, "hashB");

        // Changing the policy version or the policy content fingerprint changes the entry hash, so the
        // stamped policy cannot be altered without breaking the chain.
        assertNotEquals(v5.getHash(), v6.getHash());
        assertNotEquals(v5.getHash(), differentHash.getHash());
    }

    @Test
    void policyFieldsRoundTripThroughDocument() {
        final ObjectId userId = new ObjectId();

        final LedgerEntity entity = new LedgerEntity();
        entity.setUserId(userId);
        entity.setDocumentId("doc-1");
        entity.setToken("t");
        entity.setReplacement("r");
        entity.setTimestamp(new Date(0));
        entity.setPreviousHash("prev");
        entity.setHash("hash");
        entity.setType("PERSON");
        entity.setPolicyName("default");
        entity.setPolicyVersion(9);
        entity.setPolicyContentHash("phash");

        // A pass-through encryption service so the round-trip exercises only the field mapping.
        final ai.philterd.philter.services.encryption.EncryptionService encryptionService =
                org.mockito.Mockito.mock(ai.philterd.philter.services.encryption.EncryptionService.class);
        org.mockito.Mockito.when(encryptionService.encrypt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> new ai.philterd.philter.services.encryption.EncryptResult(inv.getArgument(0), "key"));
        org.mockito.Mockito.when(encryptionService.decrypt(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        final LedgerEntity restored = LedgerEntity.fromDocument(entity.toDocument(encryptionService), encryptionService);

        assertEquals("default", restored.getPolicyName());
        assertEquals(9, restored.getPolicyVersion());
        assertEquals("phash", restored.getPolicyContentHash());
        assertTrue(true);
    }
}
