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
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.mongodb.MongoContextEntryDataService;
import ai.philterd.philter.data.mongodb.MongoIds;
import ai.philterd.philter.data.mongodb.MongoPolicyDataService;
import ai.philterd.philter.data.mongodb.MongoPolicyVersionDataService;
import ai.philterd.philter.data.mongodb.MongoRedactListsDataService;
import ai.philterd.philter.data.mongodb.MongoVectorServices;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.context.StoredContextServices;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.testutil.AbstractMongoIT;
import ai.philterd.philter.testutil.TestEncryptionService;
import com.google.gson.Gson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An edit to the always/never redact lists or the policy governs the very next redaction, whichever instance made it.
 * The lists were cached for the cache TTL with no eviction, and a policy edit evicted only on the instance that made
 * it, so a term added to always-redact, or removed from never-redact, kept being let through for up to a minute.
 */
class RedactionCacheFreshnessIT extends AbstractMongoIT {

    private static final String POLICY_NAME = "ssn";
    private static final String REDACT_SSNS =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\",\"redactionFormat\":\"{{{REDACTED-%t}}}\"}]}}}";
    private static final String MARK_SSNS =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\",\"redactionFormat\":\"{{{SECOND-%t}}}\"}]}}}";
    private static final String SSN = "123-45-6789";
    private static final String NAME = "Zephyrine";
    private static final String TEXT = "Patient " + NAME + " has SSN " + SSN + ".";

    /**
     * Another instance's view of the shared database: its writes do not evict this instance's in-process cache. In one
     * JVM every cache shares one backend, so not evicting is what another process would look like.
     */
    private static final class OtherInstanceCache extends RedactionCache {
        @Override
        public void evictPolicy(final String userId, final String policyName) {
        }

        @Override
        public void evictRedactLists(final String userId) {
        }
    }

    private String userId;
    private RedactionService redactionService;
    private PolicyDataService policies;
    private RedactListsDataService lists;
    private PolicyDataService otherInstancePolicies;
    private RedactListsDataService otherInstanceLists;

    @BeforeEach
    void setUp() {

        userId = MongoIds.newId();
        final UserEntity user = mock(UserEntity.class);
        when(user.getId()).thenReturn(userId);
        final UserService userService = mock(UserService.class);
        when(userService.findOneById(userId)).thenReturn(user);
        when(userService.ensureFpeKey(user)).thenReturn(EncryptionService.generateFpeKey());

        final RedactionCache cache = new RedactionCache();
        policies = policies(cache);
        lists = lists(cache);
        otherInstancePolicies = policies(new OtherInstanceCache());
        otherInstanceLists = lists(new OtherInstanceCache());

        redactionService = new RedactionService(new StoredContextServices(new MongoContextEntryDataService(mongoClient, null)),
                new MongoVectorServices(mongoClient), policies, mock(CustomListDataService.class), lists,
                mock(ContextDataService.class), mock(AuditEventPublisher.class), mock(LedgerDataService.class), userService,
                new SimpleMeterRegistry(), mock(PhieldPublisher.class), mock(PiiCountAggregatePublisher.class), cache);

        assertTrue(policies.create("req", userId, REDACT_SSNS, null, null, POLICY_NAME, Source.API.getSource()).isSuccessful());

    }

    private PolicyDataService policies(final RedactionCache cache) {
        return new MongoPolicyDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class), new Gson(),
                new MongoPolicyVersionDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class)), cache);
    }

    private RedactListsDataService lists(final RedactionCache cache) {
        return new MongoRedactListsDataService(mongoClient, new TestEncryptionService(), mock(AuditEventPublisher.class), cache);
    }

    private String redact() throws Exception {
        return ((TextFilterResult) redactionService.filter(POLICY_NAME, userId, "", TEXT.getBytes(), MimeType.TEXT_PLAIN)
                .result()).getFilteredText();
    }

    @Test
    @DisplayName("A term added to always-redact is redacted by the very next request")
    void anAlwaysRedactTermAppliesAtOnce() throws Exception {
        assertTrue(redact().contains(NAME), "cached without the term first");
        lists.saveOrUpdate("req", userId, List.of(NAME), null, "test");
        assertFalse(redact().contains(NAME), "the added term must be redacted at once, not after the cache expires");
    }

    @Test
    @DisplayName("A term another instance adds to always-redact is redacted by the very next request here")
    void anAlwaysRedactTermFromAnotherInstanceAppliesAtOnce() throws Exception {
        assertTrue(redact().contains(NAME), "cached without the term first");
        otherInstanceLists.replaceList("req", userId, RedactListsDataService.RedactList.ALWAYS, List.of(NAME), null, "test");
        assertFalse(redact().contains(NAME), "a change made on another instance must apply at once");
    }

    @Test
    @DisplayName("A term another instance removes from never-redact is redacted by the very next request here")
    void aNeverRedactRemovalFromAnotherInstanceAppliesAtOnce() throws Exception {
        lists.saveOrUpdate("req", userId, null, List.of(SSN), "test");
        assertTrue(redact().contains(SSN), "a never-redact term is left in, and cached that way");
        otherInstanceLists.saveOrUpdate("req", userId, null, List.of(), "test");
        assertFalse(redact().contains(SSN), "a term removed from never-redact must be redacted at once");
    }

    @Test
    @DisplayName("A policy another instance edits governs the very next request here")
    void aPolicyEditFromAnotherInstanceAppliesAtOnce() throws Exception {
        assertTrue(redact().contains("{{{REDACTED-"), "cached at the first revision");
        assertTrue(otherInstancePolicies.update("req", userId, otherInstancePolicies.findOne(POLICY_NAME, userId).getId(),
                MARK_SSNS, null, null, Source.API.getSource()).isSuccessful());
        assertTrue(redact().contains("{{{SECOND-"), "a policy edited on another instance must apply at once");
    }

}
