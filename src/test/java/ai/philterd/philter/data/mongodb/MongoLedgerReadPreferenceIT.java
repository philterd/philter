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
package ai.philterd.philter.data.mongodb;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.services.signing.SigningService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An append decides the next entry's position, and whether the entry is stale, from the chain's latest entry, so that
 * read must come from the primary even when the client is configured to read from secondaries, which can lag.
 */
class MongoLedgerReadPreferenceIT extends AbstractMongoIT {

    @Test
    @DisplayName("The latest ledger entry is read from the primary, not with the client's read preference")
    void theLatestEntryIsReadFromThePrimary() throws Exception {

        // The ledger collection as the service sees it, with the handle it reads the primary through.
        final MongoClient client = mock(MongoClient.class, delegatesTo(mongoClient));
        final MongoDatabase database = spy(mongoClient.getDatabase("philter"));
        doReturn(database).when(client).getDatabase("philter");
        final MongoCollection<Document> ledger = spy(database.getCollection("ledger"));
        doReturn(ledger).when(database).getCollection("ledger");
        final MongoCollection<Document> fromPrimary = spy(ledger.withReadPreference(ReadPreference.primary()));
        doReturn(fromPrimary).when(ledger).withReadPreference(ReadPreference.primary());
        final MongoCollection<Document> evidence = spy(fromPrimary.withWriteConcern(WriteConcern.MAJORITY));
        doReturn(evidence).when(fromPrimary).withWriteConcern(WriteConcern.MAJORITY);

        final SigningService signing = mock(SigningService.class);
        when(signing.signLedgerEntry(anyString())).thenReturn(new SigningService.LedgerSignature("signature", "key"));
        final AuditEventPublisher audit = mock(AuditEventPublisher.class);
        final LedgerDataService service = new MongoLedgerDataService(client, new TestEncryptionService(), audit,
                new MongoLegalHoldDataService(mongoClient, audit, new MongoEvidenceGuard(mongoClient)), signing);

        final String owner = MongoIds.newId();
        service.initializeLedger(owner, "doc", "input-hash", "file.pdf", "policy", 0, "policy-hash");
        clearInvocations(ledger, evidence);

        final LedgerEntity latest = service.getLatestTransaction(owner, "doc");

        assertEquals(0, latest.getChainPosition());
        verify(evidence, times(1)).find(any(Bson.class));
        verify(ledger, never()).find(any(Bson.class));

    }

}
