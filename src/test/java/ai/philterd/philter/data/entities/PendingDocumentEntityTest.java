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

import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class PendingDocumentEntityTest {

    @Test
    void roundTripPreservesAllFields() {
        final ObjectId id = new ObjectId();
        final ObjectId userId = new ObjectId();
        final Date submitted = new Date(1_700_000_000_000L);
        final Date started = new Date(1_700_000_001_000L);
        final Date completed = new Date(1_700_000_002_000L);
        final Date claimed = new Date(1_700_000_001_500L);
        final byte[] input = new byte[]{1, 2, 3, 4};
        final byte[] output = new byte[]{5, 6, 7, 8};

        final PendingDocumentEntity original = new PendingDocumentEntity();
        original.setId(id);
        original.setUserId(userId);
        original.setDocumentId("doc-uuid");
        original.setFileName("report.pdf");
        original.setInputMimeType("APPLICATION_PDF");
        original.setOutputMimeType("application/pdf");
        original.setPolicyName("default");
        original.setContextName("none");
        original.setStatus(PendingDocumentEntity.STATUS_COMPLETE);
        original.setErrorMessage(null);
        original.setInput(input);
        original.setOutput(output);
        original.setSubmittedAt(submitted);
        original.setStartedAt(started);
        original.setCompletedAt(completed);
        original.setClaimedBy("philter-worker-x");
        original.setClaimedAt(claimed);

        final Document doc = original.toDocument();

        assertEquals(id, doc.get("_id"));
        assertEquals(userId, doc.get("user_id"));
        assertInstanceOf(Binary.class, doc.get("input"));
        assertInstanceOf(Binary.class, doc.get("output"));

        final PendingDocumentEntity restored = PendingDocumentEntity.fromDocument(doc);

        assertEquals(id, restored.getId());
        assertEquals(userId, restored.getUserId());
        assertEquals("doc-uuid", restored.getDocumentId());
        assertEquals("report.pdf", restored.getFileName());
        assertEquals("APPLICATION_PDF", restored.getInputMimeType());
        assertEquals("application/pdf", restored.getOutputMimeType());
        assertEquals("default", restored.getPolicyName());
        assertEquals("none", restored.getContextName());
        assertEquals(PendingDocumentEntity.STATUS_COMPLETE, restored.getStatus());
        assertNull(restored.getErrorMessage());
        assertArrayEquals(input, restored.getInput());
        assertArrayEquals(output, restored.getOutput());
        assertEquals(submitted, restored.getSubmittedAt());
        assertEquals(started, restored.getStartedAt());
        assertEquals(completed, restored.getCompletedAt());
        assertEquals("philter-worker-x", restored.getClaimedBy());
        assertEquals(claimed, restored.getClaimedAt());
    }

    @Test
    void toDocumentOmitsNullBinaries() {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        entity.setInput(null);
        entity.setOutput(null);

        final Document doc = entity.toDocument();

        assertNull(doc.get("input"));
        assertNull(doc.get("output"));
    }

    @Test
    void toDocumentOmitsIdWhenNull() {
        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);

        final Document doc = entity.toDocument();

        assertNull(doc.get("_id"));
    }

}
