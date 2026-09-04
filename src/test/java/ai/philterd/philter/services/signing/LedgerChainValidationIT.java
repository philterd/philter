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
import com.mongodb.client.MongoCollection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The genesis entry is the only record of which document was redacted, under which policy. */
class LedgerChainValidationIT extends AbstractMongoIT {

    private static final ObjectId USER = new ObjectId();
    private static final String DOC = "doc-genesis";

    private LedgerDataService ledgerDataService;

    @BeforeEach
    void setUp() {
        final AdminSettingsEntity settings = new AdminSettingsEntity();
        settings.setSigningEnabled(false);
        final AdminSettingsDataService adminSettings = mock(AdminSettingsDataService.class);
        when(adminSettings.findAdminSettings()).thenReturn(settings);

        final SigningService signingService = new SigningService(
                new SigningKeyDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class)),
                adminSettings);

        ledgerDataService = new LedgerDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class), mock(LegalHoldDataService.class), signingService);
    }

    private MongoCollection<Document> ledger() {
        return mongoClient.getDatabase("philter").getCollection("ledger");
    }

    private void writeChain() throws Exception {
        writeChain("token-");
    }

    private void writeChain(final String tokenPrefix) throws Exception {
        ledgerDataService.initializeLedger(USER, DOC, "input-hash", "file.txt", "default", 1, "policy-hash");
        for (int i = 0; i < 2; i++) {
            final LedgerEntity entry = new LedgerEntity();
            entry.setUserId(USER);
            entry.setDocumentId(DOC);
            entry.setToken(tokenPrefix + i);
            entry.setReplacement("{{{REDACTED-ssn}}}");
            entry.setType("ssn");
            entry.setDocumentHash("hash-" + i);
            entry.setPreviousHash(ledgerDataService.getLatestTransaction(USER, DOC).getHash());
            entry.setTimestamp(new Date());
            entry.setFilename("file.txt");
            entry.setPolicyName("default");
            entry.setPolicyVersion(1);
            entry.setPolicyContentHash("policy-hash");
            entry.setHash(entry.calculateHash());
            ledgerDataService.addTransaction(entry);
        }
    }

    /** Edits one genesis field, leaving hash and signature untouched, as a database-level rewrite would. */
    private void tamperWithGenesis(final String field, final Object value) {
        ledger().updateOne(
                Filters.and(Filters.eq("document_id", DOC), Filters.eq("previous_hash", LedgerDataService.GENESIS)),
                Updates.set(field, value));
    }

    @Test
    @DisplayName("An untampered chain validates")
    void anUntamperedChainValidates() throws Exception {
        writeChain();

        assertTrue(ledgerDataService.isChainValid(USER, DOC));
    }

    @Test
    @DisplayName("Rewriting the source document hash on the genesis entry is detected")
    void rewritingTheGenesisDocumentHashIsDetected() throws Exception {
        writeChain();

        // Every later entry's hash is untouched, so the pairwise checks alone see nothing.
        tamperWithGenesis("document_hash", "some-other-document");

        assertFalse(ledgerDataService.isChainValid(USER, DOC));
    }

    @Test
    @DisplayName("Rewriting the governing policy on the genesis entry is detected")
    void rewritingTheGenesisPolicyStampIsDetected() throws Exception {
        writeChain();

        tamperWithGenesis("policy_version", 99);

        assertFalse(ledgerDataService.isChainValid(USER, DOC));
    }

    @Test
    @DisplayName("Rewriting the filename on the genesis entry is detected")
    void rewritingTheGenesisFilenameIsDetected() throws Exception {
        writeChain();

        tamperWithGenesis("filename", "not-the-file-that-was-redacted.txt");

        assertFalse(ledgerDataService.isChainValid(USER, DOC));
    }

    @Test
    @DisplayName("Relabelling a redaction's PII type is detected")
    void relabellingAPiiTypeIsDetected() throws Exception {
        writeChain();

        final LedgerEntity target = ledgerDataService.getChain(USER, DOC).get(1);
        ledger().updateOne(Filters.eq("hash", target.getHash()), Updates.set("type", "person"));

        assertFalse(ledgerDataService.isChainValid(USER, DOC));
    }

    @Test
    @DisplayName("Deleting the genesis entry is detected")
    void deletingTheGenesisEntryIsDetected() throws Exception {
        writeChain();

        // The survivors still link to each other, so only the GENESIS marker check catches this.
        ledger().deleteOne(Filters.and(
                Filters.eq("document_id", DOC), Filters.eq("previous_hash", LedgerDataService.GENESIS)));

        assertFalse(ledgerDataService.isChainValid(USER, DOC));
    }


    /** Captures what LedgerDataService actually writes to the log. */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        CapturingAppender() {
            super("capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(final LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
        }
    }

    /** Validates with the logger captured, and returns what was logged. */
    private String logsFromValidation() throws Exception {
        final CapturingAppender appender = new CapturingAppender();
        appender.start();
        final org.apache.logging.log4j.core.Logger logger =
                (org.apache.logging.log4j.core.Logger) LogManager.getLogger(LedgerDataService.class);
        logger.addAppender(appender);
        try {
            assertFalse(ledgerDataService.isChainValid(USER, DOC), "the tampered chain must fail validation");
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
        return String.join("\n", appender.messages);
    }

    private void assertLogNamesTheEntryNotThePii(final String logged, final LedgerEntity target, final String pii) {
        assertAll(
                () -> assertFalse(logged.isEmpty(), "the failure must be logged at all"),
                () -> assertFalse(logged.contains(pii),
                        "the redacted value must never reach the log: " + logged),
                () -> assertTrue(logged.contains(target.getId().toHexString()),
                        "the log must identify the offending entry: " + logged));
    }

    @Test
    @DisplayName("A broken chain link logs the entry id, never the redacted value")
    void brokenLinkFailureLogsTheEntryNotThePii() throws Exception {

        final String ssn = "123-45-6789";
        writeChain(ssn);

        // Recompute the hash so the entry still verifies: this lands on the link-broken branch,
        // the one that logged the token at WARN.
        final LedgerEntity target = ledgerDataService.getChain(USER, DOC).get(2);
        final String storedHash = target.getHash();
        target.setPreviousHash("0000000000000000000000000000000000000000000000000000000000000000");
        ledger().updateOne(Filters.eq("hash", storedHash),
                Updates.combine(
                        Updates.set("previous_hash", target.getPreviousHash()),
                        Updates.set("hash", target.calculateHash())));

        assertLogNamesTheEntryNotThePii(logsFromValidation(), target, ssn);

    }

    @Test
    @DisplayName("A content rewrite logs the entry id, never the redacted value")
    void hashMismatchFailureLogsTheEntryNotThePii() throws Exception {

        final String ssn = "987-65-4321";
        writeChain(ssn);

        final LedgerEntity target = ledgerDataService.getChain(USER, DOC).get(1);
        ledger().updateOne(Filters.eq("hash", target.getHash()),
                Updates.set("document_hash", "rewritten"));

        assertLogNamesTheEntryNotThePii(logsFromValidation(), target, ssn);

    }

}
