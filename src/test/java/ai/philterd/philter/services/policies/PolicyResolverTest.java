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
package ai.philterd.philter.services.policies;

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.data.services.CustomListDataService;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PolicyResolverTest {

    private final Gson gson = new Gson();

    @Test
    void deserializesNativePolicy() {
        final PolicyResolver resolver = new PolicyResolver(gson, null);
        final Policy policy = resolver.resolve(
                "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}}}",
                new ObjectId(), null, null);

        assertNotNull(policy.getIdentifiers());
        assertNotNull(policy.getIdentifiers().getSsn());
    }

    @Test
    void injectsTheManagedFpeKeyWhenThePolicyHasNoFpeObject() {
        final PolicyResolver resolver = new PolicyResolver(gson, null);
        final Policy policy = resolver.resolve(
                "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"FPE_ENCRYPT_REPLACE\"}]}}}",
                new ObjectId(), "0123456789ABCDEF0123456789ABCDEF", "0123456789ABCD");

        assertNotNull(policy.getFpe(), "the managed FPE key must be injected as a fallback");
        assertEquals("0123456789ABCDEF0123456789ABCDEF", policy.getFpe().getKey());
        assertEquals("0123456789ABCD", policy.getFpe().getTweak());
    }

    @Test
    void respectsAPolicySuppliedFpeObject() {
        final PolicyResolver resolver = new PolicyResolver(gson, null);
        final Policy policy = resolver.resolve(
                "{\"fpe\":{\"key\":\"POLICYKEY\",\"tweak\":\"POLICYTWEAK\"},"
                        + "\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"FPE_ENCRYPT_REPLACE\"}]}}}",
                new ObjectId(), "MANAGEDKEY", "MANAGEDTWEAK");

        // The policy's own key must be kept; the managed key must not override it.
        assertEquals("POLICYKEY", policy.getFpe().getKey());
        assertEquals("POLICYTWEAK", policy.getFpe().getTweak());
    }

    @Test
    void expandsCustomListReferencesInIgnoredTerms() {
        final ObjectId userId = new ObjectId();

        final CustomListDataService customListService = mock(CustomListDataService.class);
        when(customListService.findItemsByNames(eq(userId), any()))
                .thenReturn(Map.of("names", List.of("alice", "bob")));

        final PolicyResolver resolver = new PolicyResolver(gson, customListService);
        final Policy policy = resolver.resolve(
                "{\"identifiers\":{\"ssn\":{\"ssnFilterStrategies\":[{\"strategy\":\"REDACT\"}]}},"
                        + "\"ignored\":[{\"name\":\"x\",\"terms\":[\"list:names\",\"keep-me\"]}]}",
                userId, null, null);

        final List<String> terms = policy.getIgnored().get(0).getTerms();
        assertTrue(terms.contains("alice"));
        assertTrue(terms.contains("bob"));
        assertTrue(terms.contains("keep-me"));
        assertEquals(3, terms.size(), "the list: reference must be expanded, not kept verbatim");
    }

    @Test
    void fetchesAllReferencedCustomListsInASingleQuery() {
        final ObjectId userId = new ObjectId();

        final CustomListDataService customListService = mock(CustomListDataService.class);
        when(customListService.findItemsByNames(eq(userId), any()))
                .thenReturn(Map.of("a", List.of("alice"), "b", List.of("bob")));

        final PolicyResolver resolver = new PolicyResolver(gson, customListService);

        // Two list references across an ignored block and a custom dictionary.
        resolver.resolve(
                "{\"identifiers\":{\"customDictionaries\":[{\"terms\":[\"list:b\",\"plain\"]}]},"
                        + "\"ignored\":[{\"name\":\"x\",\"terms\":[\"list:a\"]}]}",
                userId, null, null);

        // Both lists must be resolved with exactly one batched query, not one query per reference.
        verify(customListService, times(1)).findItemsByNames(eq(userId), any());
    }

}
