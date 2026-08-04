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
package ai.philterd.philter.views;

import ai.philterd.philter.model.ApiKeyScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The summary shown in the API Keys grid's Scopes column.
 *
 * <p>This is the only place a user sees, at a glance, what a key can do. Reporting a narrow key as
 * broad (or the reverse) would misinform the decision to keep or revoke it, so each branch is pinned
 * here rather than left to inspection.
 */
class AccountViewScopeSummaryTest {

    @Test
    @DisplayName("A key with no scopes is described as unusable, not as empty")
    void noScopesSaysTheKeyCannotBeUsed() {
        final String summary = AccountView.describeScopes(Set.of());
        assertTrue(summary.toLowerCase().contains("cannot be used"),
                "an unusable key must say so plainly; was: " + summary);
        assertEquals(summary, AccountView.describeScopes(null), "null must read the same as empty");
    }

    @Test
    @DisplayName("A key holding every scope is described as such")
    void everyScopeIsSummarised() {
        assertEquals("All scopes", AccountView.describeScopes(ApiKeyScope.all()));
    }

    @Test
    @DisplayName("A few scopes are listed by name")
    void aShortListIsShownInFull() {
        final Set<String> scopes = new LinkedHashSet<>();
        scopes.add(ApiKeyScope.REDACT.getScope());
        scopes.add(ApiKeyScope.LEDGER_READ.getScope());

        final String summary = AccountView.describeScopes(scopes);

        assertTrue(summary.contains("redact"), summary);
        assertTrue(summary.contains("ledger:read"), summary);
    }

    @Test
    @DisplayName("A longer list is summarised as a count, and never as all scopes")
    void aLongListIsCounted() {
        final Set<String> scopes = new LinkedHashSet<>();
        scopes.add(ApiKeyScope.REDACT.getScope());
        scopes.add(ApiKeyScope.LEDGER_READ.getScope());
        scopes.add(ApiKeyScope.POLICIES_READ.getScope());
        scopes.add(ApiKeyScope.CONTEXTS_READ.getScope());

        final String summary = AccountView.describeScopes(scopes);

        assertEquals("4 of " + ApiKeyScope.values().length + " scopes", summary);
        // A partially-scoped key must never read as fully privileged.
        assertTrue(!summary.equals("All scopes"), "a partial key must not be described as having all scopes");
    }

}
