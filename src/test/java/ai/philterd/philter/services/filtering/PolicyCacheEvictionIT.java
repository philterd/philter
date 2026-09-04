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
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.PolicyVersionDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redaction caches a user's policy for {@code REDACTION_CACHE_TTL_SECONDS} (60 by default). Without
 * eviction on write, an edited or deleted policy kept governing redaction for up to that long, and the
 * stale revision was stamped into the response headers and the ledger as the version that applied.
 *
 * <p>A real {@link PolicyDataService} and a real {@link RedactionCache} are used, so these assert the
 * eviction actually happens rather than that the method was called.
 */
class PolicyCacheEvictionIT extends AbstractMongoIT {

    private static final String POLICY_NAME = "ssn-policy";
    private static final String REDACT_SSNS =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\",\"redactionFormat\":\"{{{REDACTED-%t}}}\"}]}}}";
    private static final String LEAVE_SSNS =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\",\"redactionFormat\":\"{{{SECOND-%t}}}\"}]}}}";
    private static final String TEXT = "Patient SSN 123-45-6789 was filed Monday.";

    private ObjectId userId;
    private PolicyDataService policyDataService;
    private RedactionService redactionService;

    @BeforeEach
    void setUpServices() {

        userId = new ObjectId();

        final UserEntity userEntity = mock(UserEntity.class);
        when(userEntity.getId()).thenReturn(userId);

        final UserService userService = mock(UserService.class);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        when(userService.ensureFpeKey(userEntity)).thenReturn(EncryptionService.generateFpeKey());

        // One cache instance shared by the writer and the reader, as in the application.
        final RedactionCache redactionCache = new RedactionCache();

        policyDataService = new PolicyDataService(mongoClient, mock(AuditEventPublisher.class), new Gson(),
                new PolicyVersionDataService(mongoClient, mock(AuditEventPublisher.class)), redactionCache);

        final RedactListsDataService redactListsService = mock(RedactListsDataService.class);
        when(redactListsService.find(userId)).thenReturn(null);

        redactionService = new RedactionService(mongoClient, policyDataService,
                mock(CustomListDataService.class), redactListsService, mock(ContextDataService.class),
                mock(AuditEventPublisher.class), mock(LedgerDataService.class), userService,
                new SimpleMeterRegistry(), mock(PhieldPublisher.class),
                mock(PiiCountAggregatePublisher.class), redactionCache);

        assertTrue(policyDataService.create("req", userId, REDACT_SSNS, "d", "n", POLICY_NAME,
                Source.API.getSource()).isSuccessful(), "the policy under test must be created");

    }

    /** Redacts with no context, which is the cached path, and returns the outcome. */
    private RedactionOutcome redact() throws Exception {
        return redactionService.filter(POLICY_NAME, userId, "", TEXT.getBytes(), MimeType.TEXT_PLAIN);
    }

    @Test
    @DisplayName("An edited policy governs the very next redaction")
    void anEditedPolicyGovernsTheNextRedaction() throws Exception {

        final RedactionOutcome before = redact();
        assertTrue(((TextFilterResult) before.result()).getFilteredText().contains("{{{REDACTED-"),
                "the first redaction must apply the original policy");

        assertTrue(policyDataService.update("req", userId,
                policyDataService.findOne(POLICY_NAME, userId).getId(),
                LEAVE_SSNS, null, null, Source.API.getSource()).isSuccessful());

        final RedactionOutcome after = redact();

        // Content and stamped version both move immediately; before the fix each stayed stale for
        // up to the cache TTL.
        assertTrue(((TextFilterResult) after.result()).getFilteredText().contains("{{{SECOND-"),
                "the edited policy must apply at once, not after the cache TTL");
        assertNotEquals(before.appliedPolicy().version(), after.appliedPolicy().version(),
                "the stamped policy version must reflect the edit");
        assertNotEquals(before.appliedPolicy().contentHash(), after.appliedPolicy().contentHash(),
                "the stamped content hash must reflect the edit");

    }

    @Test
    @DisplayName("A rolled-back policy governs the very next redaction")
    void aRolledBackPolicyGovernsTheNextRedaction() throws Exception {

        final int original = policyDataService.findOne(POLICY_NAME, userId).getRevision();

        policyDataService.update("req", userId, policyDataService.findOne(POLICY_NAME, userId).getId(),
                LEAVE_SSNS, null, null, Source.API.getSource());

        // Redact first, so the cache holds the *edited* policy. Without this the rollback would be
        // restoring content the cache already had, and the assertion below would hold either way.
        assertTrue(((TextFilterResult) redact().result()).getFilteredText().contains("{{{SECOND-"),
                "the edited policy must be the one cached before the rollback");

        assertTrue(policyDataService.rollback("req", POLICY_NAME, userId, original).isSuccessful(),
                "the rollback must succeed");

        final RedactionOutcome after = redact();

        assertTrue(((TextFilterResult) after.result()).getFilteredText().contains("{{{REDACTED-"),
                "the rolled-back content must apply at once");

    }

    @Test
    @DisplayName("A deleted policy stops being usable at once")
    void aDeletedPolicyStopsBeingUsableAtOnce() throws Exception {

        redact();

        assertTrue(policyDataService.deleteByName("req", POLICY_NAME, userId, Source.API).isSuccessful());

        final Exception thrown = assertThrows(Exception.class, this::redact,
                "a deleted policy must not keep redacting from the cache");
        assertEquals("The policy '" + POLICY_NAME + "' does not exist.", thrown.getMessage());

    }

}
