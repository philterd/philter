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
package ai.philterd.philter.services.context;

import ai.philterd.philter.api.responses.ContextEntriesExport;
import ai.philterd.philter.api.responses.ContextEntryExport;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import com.google.gson.Gson;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import de.bwaldvogel.mongo.MongoServer;
import de.bwaldvogel.mongo.backend.memory.MemoryBackend;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

/**
 * End-to-end round-trip test for context mapping-table export/import, run against a real (in-memory)
 * MongoDB so the actual persistence queries execute. It mirrors the production flow:
 *
 * <ol>
 *   <li>A mapping is learned in a source context via the real {@code CONTEXT}-scope redaction path
 *       ({@link MongoContextService#computeReplacementIfAbsent}).</li>
 *   <li>The mapping is exported the way the export endpoint builds its payload, then serialized to
 *       JSON and parsed back (simulating transport to another environment).</li>
 *   <li>The mapping is imported into a <em>different</em> user and context via the same service call
 *       the import endpoint uses ({@link ContextEntryDataService#importEntryByHash}).</li>
 *   <li>A redaction in the destination context for the same original token resolves to the imported
 *       replacement — without regenerating it — proving identical pseudonymization is reproduced.</li>
 * </ol>
 */
class ContextExportImportIntegrationTest {

    private MongoServer server;
    private MongoClient mongoClient;
    private ContextEntryDataService contextEntryService;
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void setUp() {
        server = new MongoServer(new MemoryBackend());
        final InetSocketAddress address = server.bind();
        mongoClient = MongoClients.create("mongodb://" + address.getHostName() + ":" + address.getPort());
        auditEventPublisher = mock(AuditEventPublisher.class);
        contextEntryService = new ContextEntryDataService(mongoClient, auditEventPublisher);
    }

    @AfterEach
    void tearDown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void roundTripReproducesIdenticalPseudonymizationInAnotherContext() {

        final String token = "John Smith";
        final String filterType = "PERSON";
        final String learnedReplacement = "David Jones";

        // --- Source environment: learn a mapping through the real CONTEXT-scope redaction path. ---
        final ObjectId sourceUser = new ObjectId();
        final String sourceContext = "src";
        final MongoContextService sourceService = new MongoContextService(
                mongoClient, new ContextCache(null, 0, null, false), sourceUser, sourceContext, auditEventPublisher);

        final String firstReplacement = sourceService.computeReplacementIfAbsent(token, filterType, () -> learnedReplacement);
        assertEquals(learnedReplacement, firstReplacement);

        // --- Export: build the payload exactly as the export endpoint does. ---
        final List<ContextEntryExport> exported = new ArrayList<>();
        for (final ContextEntryEntity entry : contextEntryService.findAllByUserIdAndContext(sourceUser, sourceContext)) {
            exported.add(new ContextEntryExport(entry.getTokenHash(), entry.getReplacement(),
                    entry.getFilterType(), entry.isReplacementUuid()));
        }
        assertEquals(1, exported.size());

        // --- Transport: serialize and parse back, as if moving to another environment. ---
        final String json = new Gson().toJson(new ContextEntriesExport(sourceContext, exported));
        final ContextEntriesExport reloaded = new Gson().fromJson(json, ContextEntriesExport.class);

        // --- Import into a DIFFERENT user and context (a separate "environment"). ---
        final ObjectId destUser = new ObjectId();
        final String destContext = "dst";
        for (final ContextEntryExport entry : reloaded.getEntries()) {
            contextEntryService.importEntryByHash(destUser, destContext, entry.getTokenHash(),
                    entry.getReplacement(), entry.getFilterType(), entry.isReplacementUuid(), false);
        }

        // --- Redact in the destination context: the same input token must resolve to the imported
        //     replacement, and the "generate a new replacement" supplier must NOT be invoked. ---
        final AtomicBoolean regenerated = new AtomicBoolean(false);
        final MongoContextService destService = new MongoContextService(
                mongoClient, new ContextCache(null, 0, null, false), destUser, destContext, auditEventPublisher);

        final String resolved = destService.computeReplacementIfAbsent(token, filterType, () -> {
            regenerated.set(true);
            return "A_DIFFERENT_REPLACEMENT";
        });

        assertEquals(learnedReplacement, resolved,
                "the same input token must yield the same replacement after export/import into another context");
        assertFalse(regenerated.get(),
                "the imported mapping must be used directly, not regenerated");
    }

}
