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

import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.data.RecordIds;
import ai.philterd.philter.data.entities.LedgerEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.model.AuditLogEvent;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The guarantees of {@link LedgerDataService}. */
public interface LedgerDataServiceContract extends StorageContract {

    private void chain(final String owner, final String documentId, final List<String> tokens) throws Exception {
        final LedgerDataService ledger = storage().ledger();
        ledger.initializeLedger(owner, documentId, "input-hash", "file.pdf", "policy", 0, "policy-hash");
        for (final String token : tokens) {
            ledger.addTransaction(entry(owner, documentId, token));
        }
        ledger.completeChain(owner, documentId);
    }

    private LedgerEntity entry(final String owner, final String documentId, final String token) throws Exception {
        return new LedgerEntity(owner, documentId, token, "{{{REDACTED-" + token + "}}}", 0, "input-hash",
                storage().ledger().getLatestTransaction(owner, documentId).getHash(), "file.pdf", "ssn", "policy", 0,
                "policy-hash");
    }

    @Test
    default void ledgerAppendsKeepTheHashChainAndASealedChainTakesNoMore() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789", "987-65-4321"));
        assertTrue(storage().ledger().validateChain(owner, "doc").hashChainValid());
        final LedgerEntity late = entry(owner, "doc", "111-22-3333");
        assertThrows(IllegalStateException.class, () -> storage().ledger().addTransaction(late));
        assertTrue(storage().anotherInstance().ledger().validateChain(owner, "doc").hashChainValid());
    }

    @Test
    default void ledgerTokensAndReplacementsReadBackUnchanged() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("Ünïcödé, \"quoted\""));
        final LedgerEntity stored = storage().anotherInstance().ledger().getChain(owner, "doc").stream()
                .filter(entry -> "Ünïcödé, \"quoted\"".equals(entry.getToken())).findFirst().orElseThrow();
        assertEquals("{{{REDACTED-Ünïcödé, \"quoted\"}}}", stored.getReplacement());
    }

    @Test
    default void ledgerEntriesHeldByALegalHoldAreNotDeleted() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789"));
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc",
                "litigation", owner, owner).getStatusCode());
        assertFalse(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        assertTrue(storage().ledger().chainExists(owner, "doc"));
        assertTrue(storage().legalHolds().release("req", "case-1", owner).isSuccessful());
        assertTrue(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        assertFalse(storage().ledger().chainExists(owner, "doc"));
    }

    @Test
    default void ledgerListingsCountAndPageChains() throws Exception {
        final String owner = RecordIds.newId();
        for (int i = 0; i < 5; i++) {
            chain(owner, "doc-" + i, List.of("123-45-6789"));
        }
        assertEquals(5, storage().ledger().listChains("req", owner, null, new ai.philterd.philter.data.services.Listings.Sort("timestamp", true), 0, 1, "test").total());
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (int offset = 0; offset < 5; offset += 2) {
            storage().ledger().listChains("req", owner, null, new ai.philterd.philter.data.services.Listings.Sort("timestamp", true), offset, 2, "test").items()
                    .forEach(head -> assertTrue(seen.add(head.getDocumentId())));
        }
        assertEquals(5, seen.size());
    }

    private List<AuditLogService.AuditEvent> audited(final String owner, final AuditLogEvent event) {
        return storage().auditLog().find(owner, event.getAuditLogEvent(), null, null, 0, 100);
    }

    @Test
    default void ledgerSearchesAreAuditedWithAHashOfTheTermNeverTheTerm() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789"));
        final String term = "search-" + UUID.randomUUID();

        storage().ledger().listChains("req", owner, term, new Listings.Sort("timestamp", true), 0, 10, "test");

        final List<AuditLogService.AuditEvent> queries = audited(owner, AuditLogEvent.REDACTION_LEDGER_QUERY);
        assertEquals(1, queries.size());
        assertEquals("searchTermHash: " + DigestUtils.sha256Hex(term), queries.get(0).details());
        assertFalse(storage().storageContains(term), "the search term is stored nowhere");
    }

    @Test
    default void ledgerDeletionsAndRefusedDeletionsAreAudited() throws Exception {
        final String owner = RecordIds.newId();
        chain(owner, "doc", List.of("123-45-6789"));
        assertEquals(201, storage().legalHolds().create("req", "case-1", LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, "doc",
                "litigation", owner, owner).getStatusCode());

        assertFalse(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        final List<AuditLogService.AuditEvent> blocked = audited(owner, AuditLogEvent.LEGAL_HOLD_BLOCKED_DELETION);
        assertEquals(1, blocked.size());
        assertTrue(blocked.get(0).details().contains("case-1"), blocked.get(0).details());
        assertTrue(audited(owner, AuditLogEvent.REDACTION_LEDGER_DELETED).isEmpty());

        assertTrue(storage().legalHolds().release("req", "case-1", owner).isSuccessful());
        assertTrue(storage().ledger().deleteByDocumentId("req", owner, "doc", "test").isSuccessful());
        final List<AuditLogService.AuditEvent> deleted = audited(owner, AuditLogEvent.REDACTION_LEDGER_DELETED);
        assertEquals(1, deleted.size());
        assertEquals("documentId: doc", deleted.get(0).details());
    }

}
