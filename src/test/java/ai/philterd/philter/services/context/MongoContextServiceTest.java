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

import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.services.cache.ContextCache;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoContextServiceTest {

    @Mock
    private ContextCache contextCache;

    @Mock
    private ContextEntryDataService contextEntryService;

    private final ObjectId userId = new ObjectId();
    private final String contextName = "test-context";

    private MongoContextService service;

    @BeforeEach
    void setUp() {
        service = new MongoContextService(contextCache, contextEntryService, userId, contextName);
    }

    @Test
    void cacheHitReturnsCachedReplacementAndIncrementsReads() {
        final ObjectId entryId = new ObjectId();
        final ContextCache.CachedReplacement cached = new ContextCache.CachedReplacement(entryId, "REDACTED");

        when(contextCache.getReplacement(userId, contextName, "token")).thenReturn(cached);

        final String replacement = service.getReplacement("token");

        assertEquals("REDACTED", replacement);
        verify(contextEntryService).incrementReads(entryId);
        // Cache hit must not hit the DB lookup path
        verify(contextEntryService, never()).findOneEntryByToken(userId, contextName, "token");
    }

    @Test
    void cacheMissFetchesFromDbAndPopulatesCache() {
        final ObjectId entryId = new ObjectId();
        final ContextEntryEntity entry = new ContextEntryEntity();
        entry.setId(entryId);
        entry.setReplacement("FROM-DB");

        when(contextCache.getReplacement(userId, contextName, "token")).thenReturn(null);
        when(contextEntryService.findOneEntryByToken(userId, contextName, "token")).thenReturn(entry);

        final String replacement = service.getReplacement("token");

        assertEquals("FROM-DB", replacement);
        verify(contextEntryService).incrementReads(entryId);
        verify(contextCache).setTokenReplacement(userId, contextName, "token", entryId, "FROM-DB");
    }

    @Test
    void cacheMissAndDbMissReturnsNull() {
        when(contextCache.getReplacement(userId, contextName, "token")).thenReturn(null);
        when(contextEntryService.findOneEntryByToken(userId, contextName, "token")).thenReturn(null);

        assertNull(service.getReplacement("token"));
        verify(contextEntryService, never()).incrementReads(org.mockito.ArgumentMatchers.any());
        verify(contextCache, never()).setTokenReplacement(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(contextName),
                org.mockito.ArgumentMatchers.eq("token"),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void putReplacementWritesThroughToDbAndCache() {
        final ObjectId entryId = new ObjectId();
        final ContextEntryEntity entry = new ContextEntryEntity();
        entry.setId(entryId);
        entry.setReplacement("R");

        when(contextEntryService.findOneEntryByToken(userId, contextName, "tok"))
                .thenReturn(entry);

        service.putReplacement("tok", "R", "PERSON");

        verify(contextEntryService).putReplacement(userId, contextName, "tok", "R", "PERSON");
        verify(contextCache).setTokenReplacement(userId, contextName, "tok", entryId, "R");
    }

    @Test
    void containsTokenChecksCacheFirst() {
        when(contextCache.containsToken(userId, contextName, "tok")).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertTrue(service.containsToken("tok"));
        verify(contextEntryService, never()).containsToken(userId, contextName, "tok");
    }

    @Test
    void containsTokenFallsBackToDb() {
        when(contextCache.containsToken(userId, contextName, "tok")).thenReturn(false);
        when(contextEntryService.containsToken(userId, contextName, "tok")).thenReturn(true);
        org.junit.jupiter.api.Assertions.assertTrue(service.containsToken("tok"));
    }

}
