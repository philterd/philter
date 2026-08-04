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
package ai.philterd.philter.services.filtering;

import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import ai.philterd.philter.services.signing.SigningService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The chain head is what {@code GET /api/ledger} and the Redaction Ledgers dashboard list, so it is
 * the entry whose filename a reviewer actually sees. It previously recorded the literal
 * {@code none-provided} regardless of what the caller sent, which also made chain search by filename
 * match nothing. A real {@link LedgerDataService} is used so these assertions read what was persisted.
 */
class LedgerFilenameIT extends AbstractMongoIT {

    private static final String POLICY_NAME = "ssn-policy";
    private static final String POLICY_JSON =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    private static final String TEXT = "Patient SSN 123-45-6789 was filed Monday.";
    private static final String CONTEXT = "ctx-ledger";

    private ObjectId userId;
    private LedgerDataService ledgerDataService;
    private RedactionService redactionService;

    @BeforeEach
    void setUpServices() {

        userId = new ObjectId();

        final UserEntity userEntity = mock(UserEntity.class);
        when(userEntity.getId()).thenReturn(userId);

        final UserService userService = mock(UserService.class);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        when(userService.ensureFpeKey(userEntity)).thenReturn(EncryptionService.generateFpeKey());

        final PolicyEntity policyEntity = mock(PolicyEntity.class);
        when(policyEntity.getPolicy()).thenReturn(POLICY_JSON);
        when(policyEntity.getRevision()).thenReturn(1);
        final PolicyDataService policyDataService = mock(PolicyDataService.class);
        when(policyDataService.findOne(POLICY_NAME, userId)).thenReturn(policyEntity);

        final RedactListsDataService redactListsService = mock(RedactListsDataService.class);
        when(redactListsService.find(userId)).thenReturn(null);

        final ContextEntity context = mock(ContextEntity.class);
        when(context.getContextName()).thenReturn(CONTEXT);
        when(context.isDisambiguation()).thenReturn(false);
        when(context.isLedger()).thenReturn(true);
        final ContextDataService contextService = mock(ContextDataService.class);
        when(contextService.findOneByNameAndUserId(CONTEXT, userId)).thenReturn(context);

        final LegalHoldDataService legalHoldDataService = mock(LegalHoldDataService.class);
        ledgerDataService = new LedgerDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), legalHoldDataService, mock(SigningService.class));

        redactionService = new RedactionService(mongoClient, policyDataService,
                mock(CustomListDataService.class), redactListsService, contextService,
                mock(AuditEventPublisher.class), ledgerDataService, userService,
                new SimpleMeterRegistry(), mock(PhieldPublisher.class),
                mock(PiiCountAggregatePublisher.class), new RedactionCache());

    }

    @Test
    void chainHeadCarriesTheSubmittedFilename() throws Exception {

        redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(),
                MimeType.TEXT_PLAIN, "invoice-42.txt");

        assertEquals("invoice-42.txt", chainHead().getFilename());

    }

    @Test
    void chainHeadUsesThePlaceholderWhenNoFilenameIsGiven() throws Exception {

        redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(), MimeType.TEXT_PLAIN);

        assertEquals(RedactionService.NO_FILENAME, chainHead().getFilename());

    }

    @Test
    void aBlankFilenameFallsBackToThePlaceholder() throws Exception {

        redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(),
                MimeType.TEXT_PLAIN, "   ");

        assertEquals(RedactionService.NO_FILENAME, chainHead().getFilename());

    }

    @Test
    void everyEntryInTheChainCarriesTheSameFilename() throws Exception {

        redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(),
                MimeType.TEXT_PLAIN, "invoice-42.txt");

        final String documentId = chainHead().getDocumentId();
        final List<LedgerEntity> chain = ledgerDataService.getChain(userId, documentId);

        assertTrue(chain.size() > 1, "the chain should hold the genesis entry and at least one redaction");
        for (final LedgerEntity entry : chain) {
            assertEquals("invoice-42.txt", entry.getFilename(),
                    "the genesis entry and the redaction entries must not disagree");
        }

    }

    @Test
    void chainSearchMatchesTheSubmittedFilename() throws Exception {

        redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(),
                MimeType.TEXT_PLAIN, "invoice-42.txt");

        // Search only ever looks at chain heads, so this matched nothing while the head held the
        // placeholder.
        final List<LedgerEntity> hits = ledgerDataService.searchChainsByUserId(
                "req-search", userId, "invoice-42", 0, 25, Source.API.getSource());

        assertFalse(hits.isEmpty(), "searching by filename should find the chain");
        assertEquals("invoice-42.txt", hits.getFirst().getFilename());

    }

    @Test
    void searchIsPagedAndCountedOverTheSameMatchingSet() throws Exception {

        for (int i = 0; i < 3; i++) {
            redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(),
                    MimeType.TEXT_PLAIN, "invoice-4" + i + ".txt");
        }
        redactionService.filter(POLICY_NAME, userId, CONTEXT, TEXT.getBytes(),
                MimeType.TEXT_PLAIN, "statement-99.txt");

        assertEquals(4, ledgerDataService.countChainsByUserId(userId), "the user owns four chains");

        // The count must describe the matches, not the user's whole ledger.
        assertEquals(3, ledgerDataService.countChainsByUserIdMatching(userId, "invoice"));

        // ...and the page must be drawn from that same set.
        final List<LedgerEntity> firstPage = ledgerDataService.searchChainsByUserId(
                "req-page-1", userId, "invoice", 0, 2, Source.API.getSource());
        assertEquals(2, firstPage.size(), "a limit of 2 should return 2 of the 3 matches");

        final List<LedgerEntity> secondPage = ledgerDataService.searchChainsByUserId(
                "req-page-2", userId, "invoice", 2, 2, Source.API.getSource());
        assertEquals(1, secondPage.size(), "the second page should hold the remaining match");

        for (final LedgerEntity e : firstPage) {
            assertTrue(e.getFilename().startsWith("invoice-"), "non-matching chain leaked into the page");
        }
        assertTrue(secondPage.getFirst().getFilename().startsWith("invoice-"));

    }

    private LedgerEntity chainHead() {
        final List<LedgerEntity> heads = ledgerDataService.findChainsByUserId(
                "req-list", userId, 0, 25, Source.API.getSource());
        assertEquals(1, heads.size(), "exactly one chain should have been written");
        return heads.getFirst();
    }

}
