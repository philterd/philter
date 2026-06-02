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
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.testutil.AbstractMongoIT;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link ContextEntryDataService} against a real (in-memory) MongoDB. These
 * exercise the actual queries, the {@code getFilterTypeCounts} aggregation pipeline, user/context
 * scoping, paging, and the import conflict handling end to end — behavior the mock-based unit tests
 * can only approximate.
 */
class ContextEntryDataServiceIT extends AbstractMongoIT {

    private ContextEntryDataService service;

    @BeforeEach
    void setUpService() {
        service = new ContextEntryDataService(mongoClient, mock(AuditEventPublisher.class));
    }

    @Test
    void putThenLookUpResolvesByHashedToken() {
        final ObjectId user = new ObjectId();
        service.putReplacement(user, "ctx", "John Smith", "David Jones", "PERSON");

        assertTrue(service.containsToken(user, "ctx", "John Smith"));
        assertEquals("David Jones", service.getReplacement(user, "ctx", "John Smith"));

        final ContextEntryEntity entry = service.findOneEntryByToken(user, "ctx", "John Smith");
        assertEquals(EncryptionService.hashSha256("John Smith"), entry.getTokenHash());
        assertEquals("David Jones", entry.getReplacement());
        assertEquals("PERSON", entry.getFilterType());
    }

    @Test
    void putReplacementIsIdempotentForSameToken() {
        final ObjectId user = new ObjectId();
        service.putReplacement(user, "ctx", "John Smith", "David Jones", "PERSON");
        // A second put for the same token must not insert a duplicate or change the replacement.
        service.putReplacement(user, "ctx", "John Smith", "Someone Else", "PERSON");

        assertEquals(1, service.countByUserIdAndContext(user, "ctx"));
        assertEquals("David Jones", service.getReplacement(user, "ctx", "John Smith"));
    }

    @Test
    void getReplacementIncrementsReads() {
        final ObjectId user = new ObjectId();
        service.putReplacement(user, "ctx", "John Smith", "David Jones", "PERSON");

        service.getReplacement(user, "ctx", "John Smith");
        service.getReplacement(user, "ctx", "John Smith");

        assertEquals(2L, service.findOneEntryByToken(user, "ctx", "John Smith").getReads());
    }

    @Test
    void entriesAreScopedByUserAndContext() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.putReplacement(userA, "ctx", "John Smith", "David Jones", "PERSON");

        // Same token, different user — not visible.
        assertFalse(service.containsToken(userB, "ctx", "John Smith"));
        assertNull(service.getReplacement(userB, "ctx", "John Smith"));

        // Same token and user, different context — not visible.
        assertFalse(service.containsToken(userA, "other", "John Smith"));
    }

    @Test
    void importSkipKeepsExistingAndOverwriteReplaces() {
        final ObjectId user = new ObjectId();
        final String hash = EncryptionService.hashSha256("John Smith");

        assertEquals(ContextEntryDataService.ImportOutcome.INSERTED,
                service.importEntryByHash(user, "ctx", hash, "R1", "PERSON", false, false));

        // skip: existing replacement is preserved.
        assertEquals(ContextEntryDataService.ImportOutcome.SKIPPED,
                service.importEntryByHash(user, "ctx", hash, "R2", "PERSON", false, false));
        assertEquals("R1", service.getReplacement(user, "ctx", "John Smith"));
        assertEquals(1, service.countByUserIdAndContext(user, "ctx"));

        // overwrite: existing replacement is replaced, still a single entry.
        assertEquals(ContextEntryDataService.ImportOutcome.OVERWRITTEN,
                service.importEntryByHash(user, "ctx", hash, "R3", "PERSON", false, true));
        assertEquals("R3", service.getReplacement(user, "ctx", "John Smith"));
        assertEquals(1, service.countByUserIdAndContext(user, "ctx"));
    }

    @Test
    void getFilterTypeCountsAggregatesExcludingUuidReplacements() {
        final ObjectId user = new ObjectId();
        service.putReplacement(user, "ctx", "John Smith", "David Jones", "PERSON");
        service.putReplacement(user, "ctx", "Jane Roe", "Mary Major", "PERSON");
        service.putReplacement(user, "ctx", "john@example.com", "noone@example.com", "EMAIL_ADDRESS");
        // A UUID replacement must be excluded from the counts.
        service.putReplacement(user, "ctx", "123-45-6789", "550e8400-e29b-41d4-a716-446655440000", "SSN");

        final Map<String, Long> counts = service.getFilterTypeCounts("ctx", user);

        assertEquals(2L, counts.get("PERSON"));
        assertEquals(1L, counts.get("EMAIL_ADDRESS"));
        assertFalse(counts.containsKey("SSN"), "UUID replacements must be excluded from filter-type counts");
    }

    @Test
    void deleteByContextNameRemovesEntriesForAnyContext() {
        final ObjectId user = new ObjectId();
        service.putReplacement(user, "ctx", "John Smith", "David Jones", "PERSON");
        service.putReplacement(user, "default", "Jane Roe", "Mary Major", "PERSON");

        service.deleteByContextName("ctx", user);
        assertEquals(0, service.countByUserIdAndContext(user, "ctx"));

        // The "default" context is no longer special; its entries are deleted like any other.
        service.deleteByContextName("default", user);
        assertEquals(0, service.countByUserIdAndContext(user, "default"));
    }

    @Test
    void deleteByIdAndUserIdScopesToOwningUser() {
        final ObjectId userA = new ObjectId();
        final ObjectId userB = new ObjectId();
        service.putReplacement(userA, "ctx", "John Smith", "David Jones", "PERSON");
        final ObjectId entryId = service.findOneEntryByToken(userA, "ctx", "John Smith").getId();

        // A different user cannot delete the entry.
        assertEquals(0L, service.deleteByIdAndUserId(entryId, userB));
        assertEquals(1, service.countByUserIdAndContext(userA, "ctx"));

        // The owning user can.
        assertEquals(1L, service.deleteByIdAndUserId(entryId, userA));
        assertEquals(0, service.countByUserIdAndContext(userA, "ctx"));
    }

    @Test
    void findAllSupportsPagingAndUnboundedReads() {
        final ObjectId user = new ObjectId();
        for (int i = 0; i < 5; i++) {
            service.putReplacement(user, "ctx", "token-" + i, "R" + i, "PERSON");
        }

        assertEquals(5, service.countByUserIdAndContext(user, "ctx"));
        assertEquals(2, service.findAllByUserIdAndContext(user, "ctx", 0, 2).size());
        assertEquals(2, service.findAllByUserIdAndContext(user, "ctx", 2, 2).size());
        assertEquals(1, service.findAllByUserIdAndContext(user, "ctx", 4, 2).size());

        final List<ContextEntryEntity> all = service.findAllByUserIdAndContext(user, "ctx");
        assertEquals(5, all.size());
    }

}
