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
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.*;
import ai.philterd.philter.services.cache.RedactionCache;
import ai.philterd.philter.services.diffuse.PiiCountAggregatePublisher;
import ai.philterd.philter.services.phield.PhieldPublisher;
import ai.philterd.philter.services.policies.PolicyResolutionException;
import com.mongodb.client.MongoClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bson.types.ObjectId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class RedactionPolicyResolutionTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingDependencyStopsLiveAndPinnedRedactionBeforeOutput(final boolean pinned) {
        final var users = mock(UserService.class);
        final var policies = mock(PolicyDataService.class);
        final var lists = mock(CustomListDataService.class);
        final var ledger = mock(LedgerDataService.class);
        final var contexts = mock(ContextDataService.class);
        final var redactLists = mock(RedactListsDataService.class);
        final var phield = mock(PhieldPublisher.class);
        final var counts = mock(PiiCountAggregatePublisher.class);
        final var service = new RedactionService(mock(MongoClient.class), policies, lists, redactLists,
                contexts, mock(AuditEventPublisher.class), ledger, users, new SimpleMeterRegistry(),
                phield, counts, new RedactionCache());
        final var user = new UserEntity();
        user.setId(new ObjectId());
        when(users.findOneById(user.getId())).thenReturn(user);
        when(users.ensureFpeKey(user)).thenReturn("ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100");
        final String json = "{\"identifiers\":{\"dictionaries\":[{\"terms\":[\"list:missing\"]}]}}";
        if (!pinned) {
            final var policy = new PolicyEntity();
            policy.setPolicy(json);
            when(policies.findOne("p", user.getId())).thenReturn(policy);
        }
        final PinnedPolicy snapshot = pinned ? new PinnedPolicy("p", 1, "hash", json) : null;
        assertThrows(PolicyResolutionException.class, () -> service.filter("p", user.getId(), "",
                "Sensitive text".getBytes(java.nio.charset.StandardCharsets.UTF_8), MimeType.TEXT_PLAIN,
                snapshot, null, null));
        verifyNoInteractions(ledger, contexts, redactLists, phield, counts);
    }
}
