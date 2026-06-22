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
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.LedgerDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.testutil.AbstractMongoIT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end regression that the per-context span-disambiguation setting is honored on every request,
 * through the real Phileas filter engine, even though {@link RedactionService} now reuses a cached
 * {@link ai.philterd.phileas.PhileasConfiguration} per on/off variant.
 *
 * <p>The observable difference is the disambiguation engine's training side effect: with an enabled,
 * context-scoped context the engine stores vectors in the {@code vectors} collection via
 * {@code MongoVectorService}; with disambiguation disabled it is a no-op and writes nothing. A
 * rule-based SSN filter keeps detection local and deterministic (no model server). Both orderings are
 * exercised so that whichever configuration is cached first cannot leak into the other request.
 */
class RedactionDisambiguationIT extends AbstractMongoIT {

    // Rule-based SSN detection keeps the engine local and deterministic; non-stop-word context words
    // around the SSN guarantee the disambiguation engine produces a non-empty training vector.
    private static final String POLICY_NAME = "ssn-policy";
    private static final String POLICY_JSON =
            "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}";
    private static final String TEXT = "Patient SSN 123-45-6789 was filed Monday.";

    private ObjectId userId;
    private ContextDataService contextService;
    private RedactionService redactionService;

    @BeforeEach
    void setUpServices() {

        userId = new ObjectId();

        final UserEntity userEntity = mock(UserEntity.class);
        when(userEntity.getId()).thenReturn(userId);

        final UserService userService = mock(UserService.class);
        when(userService.findOneById(userId)).thenReturn(userEntity);
        // REDACT does not use the key, but RedactionService always resolves one; give it a valid value.
        when(userService.ensureFpeKey(userEntity)).thenReturn(EncryptionService.generateFpeKey());

        final PolicyEntity policyEntity = mock(PolicyEntity.class);
        when(policyEntity.getPolicy()).thenReturn(POLICY_JSON);
        when(policyEntity.getRevision()).thenReturn(1);
        final PolicyDataService policyDataService = mock(PolicyDataService.class);
        when(policyDataService.findOne(POLICY_NAME, userId)).thenReturn(policyEntity);

        final RedactListsDataService redactListsService = mock(RedactListsDataService.class);
        when(redactListsService.find(userId)).thenReturn(null);

        contextService = mock(ContextDataService.class);

        redactionService = new RedactionService(mongoClient, policyDataService,
                mock(CustomListDataService.class), redactListsService, contextService,
                mock(AuditEventPublisher.class), mock(LedgerDataService.class), userService,
                new SimpleMeterRegistry(), mock(PhieldPublisher.class),
                mock(PiiCountAggregatePublisher.class), new RedactionCache());

    }

    @Test
    void disabledContextDoesNotDisambiguateAfterAnEnabledRequest() throws Exception {

        stubContext("ctx-on", true);
        stubContext("ctx-off", false);

        redact("ctx-on");
        final long afterEnabled = vectorCount();
        assertTrue(afterEnabled > 0,
                "an enabled context must engage the disambiguation engine and store training vectors");

        redact("ctx-off");
        assertEquals(afterEnabled, vectorCount(),
                "a disabled context must not disambiguate, even after an enabled request cached the enabled configuration");

    }

    @Test
    void enabledContextStillDisambiguatesAfterADisabledRequest() throws Exception {

        stubContext("ctx-off", false);
        stubContext("ctx-on", true);

        redact("ctx-off");
        assertEquals(0, vectorCount(),
                "a disabled context must not store training vectors");

        redact("ctx-on");
        assertTrue(vectorCount() > 0,
                "an enabled context must still disambiguate, even after a disabled request cached the disabled configuration");

    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void stubContext(final String name, final boolean disambiguationEnabled) {
        final ContextEntity context = mock(ContextEntity.class);
        when(context.getContextName()).thenReturn(name);
        when(context.isDisambiguation()).thenReturn(disambiguationEnabled);
        when(context.isLedger()).thenReturn(false);
        // Context scope persists the engine's vectors to Mongo, which is what makes the enabled case
        // observable. Only read when disambiguation is enabled.
        when(context.getDisambiguationScope()).thenReturn("Context");
        when(contextService.findOneByNameAndUserId(name, userId)).thenReturn(context);
    }

    private void redact(final String contextName) throws Exception {
        redactionService.filter(POLICY_NAME, userId, contextName, TEXT.getBytes(), MimeType.TEXT_PLAIN);
    }

    private long vectorCount() {
        return mongoClient.getDatabase("philter").getCollection("vectors").countDocuments();
    }

}
