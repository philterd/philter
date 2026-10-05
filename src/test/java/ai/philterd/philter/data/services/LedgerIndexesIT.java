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
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerIndexesIT extends AbstractMongoIT {

    private LedgerDataService service;

    @BeforeEach
    void setUp() throws Exception {
        final SigningService signer = mock(SigningService.class);
        when(signer.signLedgerEntry(anyString())).thenReturn(new SigningService.LedgerSignature("signature", "key"));
        service = new LedgerDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class),
                mock(LegalHoldDataService.class), signer);
    }

    private List<Document> indexKeys() {
        final List<Document> keys = new ArrayList<>();
        for (final Document index : mongoClient.getDatabase("philter").getCollection("ledger").listIndexes()) {
            keys.add(index.get("key", Document.class));
        }
        return keys;
    }

    @Test
    void createsTheIndexForChainHeadsAcrossUsers() {
        final List<Document> keys = indexKeys();
        assertTrue(keys.contains(new Document("previous_hash", 1).append("timestamp", -1).append("_id", -1)), keys.toString());
        // The per-user indexes are still there.
        assertTrue(keys.contains(new Document("user_id", 1).append("previous_hash", 1).append("timestamp", 1)), keys.toString());
        assertTrue(keys.contains(new Document("user_id", 1).append("document_id", 1).append("timestamp", 1)), keys.toString());
    }

    @Test
    void listsAndCountsChainHeadsAcrossUsersAsBefore() throws Exception {
        final ObjectId alice = new ObjectId();
        final ObjectId bob = new ObjectId();
        service.initializeLedger(alice, "doc-a", "hash", "a.txt", "default", 0, "policy-hash");
        service.initializeLedger(bob, "doc-b", "hash", "b.txt", "default", 0, "policy-hash");
        service.initializeLedger(alice, "doc-c", "hash", "c.txt", "default", 0, "policy-hash");

        assertEquals(3, service.countAllChainHeads());
        final List<LedgerEntity> heads = service.findAllChainHeadsAcrossUsers(0, 25);
        assertEquals(List.of("doc-c", "doc-b", "doc-a"), heads.stream().map(LedgerEntity::getDocumentId).toList(),
                "newest first");
        assertEquals(List.of("doc-b"), service.findAllChainHeadsAcrossUsers(1, 1).stream().map(LedgerEntity::getDocumentId).toList());
    }

}
