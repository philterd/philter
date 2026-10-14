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

import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.philter.data.mongodb.MongoContextEntryDataService;
import ai.philterd.philter.api.responses.ContextEntriesExport;
import ai.philterd.philter.api.responses.ContextEntryExport;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.mongodb.MongoIds;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * End-to-end round-trip test for context mapping-table export/import, run against a real (in-memory)
 * MongoDB so the actual persistence queries execute. It mirrors the production flow:
 *
 * <ol>
 *   <li>A mapping is learned in a source context via the real {@code CONTEXT}-scope redaction path
 *       ({@link ContextService#computeReplacementIfAbsent}).</li>
 *   <li>The mapping is exported the way the export endpoint builds its payload, then serialized to
 *       JSON and parsed back (simulating transport to another environment).</li>
 *   <li>The mapping is imported into a <em>different</em> user and context via the same service call
 *       the import endpoint uses ({@link ContextEntryDataService#importEntryByHash}).</li>
 *   <li>A redaction in the destination context for the same original token resolves to the imported
 *       replacement — without regenerating it — proving identical pseudonymization is reproduced.</li>
 * </ol>
 */
class ContextExportImportIT extends AbstractMongoIT {

    private ContextEntryDataService contextEntryService;
    private AuditEventPublisher auditEventPublisher;

    @BeforeEach
    void setUpServices() {
        auditEventPublisher = mock(AuditEventPublisher.class);
        contextEntryService = new MongoContextEntryDataService(mongoClient, new ContextCache(null, 0, null, false));
    }

    private static final String TOKEN = "John Smith";
    private static final String FILTER_TYPE = "PERSON";
    private static final String LEARNED = "David Jones";

    /** Learns a mapping through the real redaction path and exports it as the export endpoint does. */
    private ContextEntriesExport learnAndExport(final String user, final String context) {

        final ContextService source = new ai.philterd.philter.services.context.StoredContextServices(
                new MongoContextEntryDataService(mongoClient, null)).forContext(new ContextCache(null, 0, null, false), user, context);
        assertEquals(LEARNED, source.computeReplacementIfAbsent(TOKEN, FILTER_TYPE, () -> LEARNED));

        final List<ContextEntryExport> exported = new ArrayList<>();
        for (final ContextEntryEntity entry : contextEntryService.findAllByUserIdAndContext(user, context)) {
            exported.add(new ContextEntryExport(entry.getTokenHash(), entry.getReplacement(),
                    entry.getFilterType(), entry.isReplacementUuid()));
        }
        assertEquals(1, exported.size());

        // Serialized and parsed back, as if carried to where it is imported.
        final String json = new Gson().toJson(new ContextEntriesExport(context, exported));
        return new Gson().fromJson(json, ContextEntriesExport.class);

    }

    /** Imports the export, then redacts the same token there and returns what replaced it, or null if regenerated. */
    private String importAndRedact(final ContextEntriesExport export, final String user, final String context) {

        for (final ContextEntryExport entry : export.getEntries()) {
            contextEntryService.importEntryByHash(user, context, entry.getTokenHash(),
                    entry.getReplacement(), entry.getFilterType(), entry.isReplacementUuid(), false);
        }

        final AtomicBoolean regenerated = new AtomicBoolean(false);
        final ContextService destination = new ai.philterd.philter.services.context.StoredContextServices(
                new MongoContextEntryDataService(mongoClient, null)).forContext(new ContextCache(null, 0, null, false), user, context);
        final String resolved = destination.computeReplacementIfAbsent(TOKEN, FILTER_TYPE, () -> {
            regenerated.set(true);
            return "A_DIFFERENT_REPLACEMENT";
        });
        return regenerated.get() ? null : resolved;

    }

    @Test
    void roundTripReproducesIdenticalPseudonymizationInAnotherContext() {

        final String user = newUser();
        final ContextEntriesExport export = learnAndExport(user, "src");

        assertEquals(LEARNED, importAndRedact(export, user, "dst"),
                "the same input token must yield the same replacement after export/import into another of the user's "
                        + "contexts, used directly rather than regenerated");

    }

    @Test
    void anotherUsersExportDoesNotMatchTheirTokens() {

        // Tokens are hashed per user, so one user cannot import another's export and redact guesses to learn which
        // values it holds and what replaced them.
        final ContextEntriesExport export = learnAndExport(newUser(), "src");

        assertNull(importAndRedact(export, newUser(), "dst"),
                "another user's imported hashes must not match the same token, so a new replacement is made");

    }

}
