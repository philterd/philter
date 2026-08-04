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

import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The submitted document is the most sensitive thing Philter holds: by definition it is the file the
 * customer believes contains PII or PHI. It is stored in {@code pending_documents} for the whole
 * queue-to-completion window, so it must not be readable from a database dump alone.
 */
class PendingDocumentEncryptionIT extends AbstractMongoIT {

    private static final ObjectId USER = new ObjectId();
    private static final String SECRET = "Patient John Smith, SSN 123-45-6789, admitted 2026-01-04.";

    private PendingDocumentDataService service;

    @BeforeEach
    void setUp() {
        service = new PendingDocumentDataService(mongoClient, new TestEncryptionService(),
                mock(AuditEventPublisher.class));
    }

    private PendingDocumentEntity newPending(final byte[] input) {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setUserId(USER);
        entity.setDocumentId("doc-1");
        entity.setPolicyName("default");
        entity.setContextName("");
        entity.setInputMimeType(MimeType.APPLICATION_PDF.name());
        entity.setOutputMimeType(MimeType.APPLICATION_PDF.name());
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        entity.setInput(input);
        entity.setFileName("admission.pdf");
        entity.setSubmittedAt(new Date());
        return entity;
    }

    /** Reads the raw stored document, bypassing the entity, as a database dump would. */
    private Document raw() {
        return mongoClient.getDatabase("philter").getCollection("pending_documents").find().first();
    }

    @Test
    void theStoredDocumentDoesNotContainTheSubmittedBytes() {
        service.save(newPending(SECRET.getBytes(StandardCharsets.UTF_8)));

        final Document stored = raw();
        assertNotNull(stored);

        final byte[] storedInput = ((Binary) stored.get("input")).getData();
        final String asText = new String(storedInput, StandardCharsets.UTF_8);

        assertFalse(asText.contains("John Smith"), "the submitted document must not be readable at rest");
        assertFalse(asText.contains("123-45-6789"), "the submitted document must not be readable at rest");
        assertFalse(java.util.Arrays.equals(SECRET.getBytes(StandardCharsets.UTF_8), storedInput));

        // The wrapped per-record key is what makes it recoverable, and only with the master key.
        assertNotNull(stored.getString("input_encrypted_key"), "the wrapped data key must be stored");
    }

    @Test
    void theDocumentRoundTripsThroughEncryption() {
        service.save(newPending(SECRET.getBytes(StandardCharsets.UTF_8)));

        final PendingDocumentEntity read = service.findOneByDocumentIdAndUserId("doc-1", USER);

        assertArrayEquals(SECRET.getBytes(StandardCharsets.UTF_8), read.getInput());
    }

    @Test
    void aTenMegabyteDocumentStillFitsUnderTheBsonLimit() {
        // Encrypting bytes as bytes costs the IV and tag. Routing them through the String path would
        // base64 twice, expanding 10 MB to roughly 17.8 MB and exceeding MongoDB's 16 MB limit.
        final byte[] large = new byte[10 * 1024 * 1024];
        new java.util.Random(42).nextBytes(large);

        service.save(newPending(large));

        final Document stored = raw();
        final byte[] storedInput = ((Binary) stored.get("input")).getData();

        assertTrue(storedInput.length < 16 * 1024 * 1024,
                "stored ciphertext must stay under the BSON document limit, was " + storedInput.length);
        assertTrue(storedInput.length - large.length < 1024,
                "byte-oriented encryption should cost only the IV and tag, grew by "
                        + (storedInput.length - large.length));

        assertArrayEquals(large, service.findOneByDocumentIdAndUserId("doc-1", USER).getInput());
    }

    @Test
    void theRedactedOutputIsEncryptedToo() {
        service.save(newPending(SECRET.getBytes(StandardCharsets.UTF_8)));
        final PendingDocumentEntity claimed = service.claimNextPending("w1");

        final byte[] redacted = "Patient {{{REDACTED-name}}}, SSN {{{REDACTED-ssn}}}.".getBytes(StandardCharsets.UTF_8);
        service.markComplete(claimed.getId(), USER, redacted);

        // markComplete is a partial update that never passes through toDocument, so it has to
        // encrypt on its own; without that it wrote the redacted document in the clear.
        final Document stored = raw();
        final byte[] storedOutput = ((Binary) stored.get("output")).getData();
        assertFalse(java.util.Arrays.equals(redacted, storedOutput), "the output must be encrypted at rest");
        assertNotNull(stored.getString("output_encrypted_key"));

        final PendingDocumentEntity read = service.findOneByDocumentIdAndUserId("doc-1", USER);
        assertArrayEquals(redacted, read.getOutput());
        assertNull(read.getInput(), "the submitted document must be discarded once complete");
        assertNull(stored.getString("input_encrypted_key"), "its wrapped key must go with it");
    }

    @Test
    void aJobThatCanNeverCompleteIsFailedRatherThanRetriedForever() {
        final PendingDocumentEntity poison = newPending(SECRET.getBytes(StandardCharsets.UTF_8));
        poison.setStatus(PendingDocumentEntity.STATUS_PROCESSING);
        poison.setClaimedBy("dead-worker");
        poison.setClaimedAt(new Date(0));
        poison.setReclaimCount(3);
        service.save(poison);

        // At the cap the job is failed rather than returned to the queue, which sets completed_at so
        // the TTL can collect it. Otherwise its plaintext input would be retained indefinitely.
        final long reclaimed = service.reclaimStuckJobs(new Date(), 3);
        assertEquals(0, reclaimed, "an exhausted job must not be returned to the queue");

        final PendingDocumentEntity failed = service.findOneByDocumentIdAndUserId("doc-1", USER);
        assertEquals(PendingDocumentEntity.STATUS_FAILED, failed.getStatus());
        assertNotNull(failed.getCompletedAt(), "completed_at must be set so the TTL applies");
        assertNull(failed.getInput(), "the submitted document must not be retained");
    }

    @Test
    void aJobBelowTheCapIsStillRetried() {
        final PendingDocumentEntity stuck = newPending(SECRET.getBytes(StandardCharsets.UTF_8));
        stuck.setStatus(PendingDocumentEntity.STATUS_PROCESSING);
        stuck.setClaimedBy("dead-worker");
        stuck.setClaimedAt(new Date(0));
        stuck.setReclaimCount(1);
        service.save(stuck);

        assertEquals(1, service.reclaimStuckJobs(new Date(), 3));

        final PendingDocumentEntity requeued = service.findOneByDocumentIdAndUserId("doc-1", USER);
        assertEquals(PendingDocumentEntity.STATUS_PENDING, requeued.getStatus());
        assertEquals(2, requeued.getReclaimCount(), "the attempt must be counted");
    }

}
