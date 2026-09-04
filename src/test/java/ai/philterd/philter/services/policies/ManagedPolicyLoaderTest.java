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
import ai.philterd.philter.data.entities.PolicyEntity;
import com.google.gson.Gson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The loader swallows a per-file failure, so a policy that stops loading disappears silently. */
class ManagedPolicyLoaderTest {

    private static final Gson GSON = new Gson();

    @Test
    @DisplayName("Every built-in managed policy is loaded")
    void everyManagedPolicyIsLoaded() {

        final List<PolicyEntity> policies = new ManagedPolicyLoader(GSON).loadManagedPolicies();

        assertEquals(3, policies.size(),
                "all three built-in policies must load; got " + policies.stream().map(PolicyEntity::getName).toList());

        assertTrue(policies.stream().anyMatch(p -> "managed_common_pii".equals(p.getName())));
        assertTrue(policies.stream().anyMatch(p -> "managed_healthcare_phi".equals(p.getName())));
        assertTrue(policies.stream().anyMatch(p -> "managed_financial_pii".equals(p.getName())));

    }

    @Test
    @DisplayName("Each loaded policy carries parseable content and is marked managed")
    void eachPolicyCarriesParseableContent() {

        for (final PolicyEntity policy : new ManagedPolicyLoader(GSON).loadManagedPolicies()) {

            assertNotNull(policy.getPolicy(), policy.getName() + " must carry its JSON");
            assertFalse(policy.getPolicy().isBlank(), policy.getName() + " must not be empty");

            // Parse again: a truncated read would still produce a non-blank string.
            final Policy parsed = GSON.fromJson(policy.getPolicy(), Policy.class);
            assertNotNull(parsed, policy.getName() + " must parse as a Phileas policy");
            assertNotNull(parsed.getIdentifiers(), policy.getName() + " must declare identifiers");

            assertTrue(policy.isManaged(), policy.getName() + " must be marked managed");
            assertNotNull(policy.getDescription(), policy.getName() + " must carry a description");

        }

    }

    @Test
    @DisplayName("Policy JSON is read whole, not truncated")
    void policyJsonIsReadWhole() {

        final PolicyEntity common = new ManagedPolicyLoader(GSON).loadManagedPolicies().stream()
                .filter(p -> "managed_common_pii".equals(p.getName()))
                .findFirst()
                .orElseThrow();

        // Against the resource itself, since a partial read can still parse.
        final byte[] expected;
        try (final var in = getClass().getResourceAsStream("/managed-policies/common-pii.json")) {
            assertNotNull(in);
            expected = in.readAllBytes();
        } catch (final Exception e) {
            throw new AssertionError("the resource must be readable", e);
        }

        assertEquals(new String(expected, java.nio.charset.StandardCharsets.UTF_8), common.getPolicy());

    }

}
