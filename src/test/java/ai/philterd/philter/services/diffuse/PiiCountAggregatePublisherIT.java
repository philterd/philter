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
package ai.philterd.philter.services.diffuse;

import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link PiiCountAggregatePublisher} against a real (in-memory) MongoDB. These
 * exercise the upsert into the {@code pii_count_aggregates} collection, the per-(context, daily
 * bucket) document-presence increments, idempotent bucketing across repeated calls, and the
 * disabled-by-admin-setting no-op path — using a real {@link AdminSettingsDataService} so the actual
 * setting read and the actual storage writes both run.
 */
class PiiCountAggregatePublisherIT extends AbstractMongoIT {

    private AdminSettingsDataService adminSettingsDataService;
    private MongoCollection<Document> aggregates;

    @BeforeEach
    void setUpServices() {
        adminSettingsDataService = new AdminSettingsDataService(mongoClient, mock(AuditEventPublisher.class));
        aggregates = mongoClient.getDatabase("philter").getCollection(PiiCountAggregatePublisher.COLLECTION);
    }

    private PiiCountAggregatePublisher enabledPublisher() {
        adminSettingsDataService.saveDiffuseCountsEnabled(true);
        return new PiiCountAggregatePublisher(mongoClient, adminSettingsDataService);
    }

    @Test
    void recordWritesAggregateDocumentWithCounts() {
        final PiiCountAggregatePublisher publisher = enabledPublisher();

        publisher.record("ctx", Set.of("PERSON", "EMAIL_ADDRESS"));

        final Document doc = aggregates.find(new Document("context", "ctx")).first();
        assertNotNull(doc, "an aggregate document must be written for the context");
        assertNotNull(doc.get("bucket_start"), "the daily bucket start must be set");
        assertNotNull(doc.get("updated_at"), "updated_at must be set");
        assertEquals(1, doc.getInteger("total_documents"));

        final Document counts = doc.get("counts", Document.class);
        assertEquals(1, counts.getInteger("PERSON"));
        assertEquals(1, counts.getInteger("EMAIL_ADDRESS"));
    }

    @Test
    void recordTwiceUpsertsIntoSameDailyBucket() {
        final PiiCountAggregatePublisher publisher = enabledPublisher();

        publisher.record("ctx", Set.of("PERSON", "EMAIL_ADDRESS"));
        publisher.record("ctx", Set.of("PERSON", "PHONE_NUMBER"));

        // Both records land in the same (context, day) bucket — a single document, not a duplicate.
        assertEquals(1L, aggregates.countDocuments(new Document("context", "ctx")));

        final Document doc = aggregates.find(new Document("context", "ctx")).first();
        assertNotNull(doc);
        assertEquals(2, doc.getInteger("total_documents"));

        final Document counts = doc.get("counts", Document.class);
        assertEquals(2, counts.getInteger("PERSON"), "PERSON present in both redactions");
        assertEquals(1, counts.getInteger("EMAIL_ADDRESS"));
        assertEquals(1, counts.getInteger("PHONE_NUMBER"));
    }

    @Test
    void recordScopesAggregatesByContext() {
        final PiiCountAggregatePublisher publisher = enabledPublisher();

        publisher.record("ctxA", Set.of("PERSON"));
        publisher.record("ctxB", Set.of("PERSON"));

        // Distinct contexts produce distinct aggregate documents.
        assertEquals(1L, aggregates.countDocuments(new Document("context", "ctxA")));
        assertEquals(1L, aggregates.countDocuments(new Document("context", "ctxB")));
    }

    @Test
    void recordIsNoOpWhenDisabled() {
        // diffuse_counts_enabled defaults to off; do not enable it.
        final PiiCountAggregatePublisher publisher =
                new PiiCountAggregatePublisher(mongoClient, adminSettingsDataService);

        publisher.record("ctx", Set.of("PERSON"));

        assertEquals(0L, aggregates.countDocuments(), "nothing must be written when disabled");
        assertNull(aggregates.find(new Document("context", "ctx")).first());
    }

    @Test
    void recordIsNoOpForEmptyPiiTypesEvenWhenEnabled() {
        final PiiCountAggregatePublisher publisher = enabledPublisher();

        publisher.record("ctx", Set.of());

        assertEquals(0L, aggregates.countDocuments(), "no document for a redaction with no PII types");
    }

}
