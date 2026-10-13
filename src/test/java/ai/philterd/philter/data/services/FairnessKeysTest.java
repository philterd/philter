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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairnessKeysTest {

    /** Admits documents with these keys, one after another, and returns their keys in the order they would be claimed. */
    private static List<String> claimOrder(final String... keys) {
        final List<FairnessKeys.Queued> pending = new ArrayList<>();
        long round = 0;
        for (int i = 0; i < keys.length; i++) {
            final FairnessKeys.Plan plan = FairnessKeys.plan(pending, keys[i], ++round);
            final List<FairnessKeys.Queued> moved = new ArrayList<>();
            for (final FairnessKeys.Queued queued : pending) {
                moved.add(new FairnessKeys.Queued(queued.id(), queued.key(),
                        plan.reassigned().getOrDefault(queued.id(), queued.round())));
            }
            moved.add(new FairnessKeys.Queued(keys[i] + "#" + i, keys[i], plan.round()));
            moved.sort(Comparator.comparingLong(FairnessKeys.Queued::round));
            pending.clear();
            pending.addAll(moved);
        }
        return pending.stream().map(FairnessKeys.Queued::id).toList();
    }

    @Test
    void documentsWithoutAKeyKeepTheirOrderAndRounds() {
        final List<FairnessKeys.Queued> pending = List.of(new FairnessKeys.Queued("a", null, 3),
                new FairnessKeys.Queued("b", null, 7));
        final FairnessKeys.Plan plan = FairnessKeys.plan(pending, null, 8);
        assertEquals(8, plan.round());
        assertEquals(Map.of(), plan.reassigned(), "nothing moves when no document has a key");
    }

    @Test
    void documentsWithOneKeyKeepTheirOrderAndRounds() {
        final List<FairnessKeys.Queued> pending = List.of(new FairnessKeys.Queued("a", "k", 3),
                new FairnessKeys.Queued("b", "k", 7));
        final FairnessKeys.Plan plan = FairnessKeys.plan(pending, "k", 8);
        assertEquals(8, plan.round());
        assertEquals(Map.of(), plan.reassigned());
    }

    @Test
    void keysTakeTurns() {
        assertEquals(List.of("a#0", "b#3", "a#1", "b#4", "a#2"), claimOrder("a", "a", "a", "b", "b"));
    }

    @Test
    void aNewKeyWaitsForOneTurnOfEachKeyAlreadyQueued() {
        assertEquals(List.of("a#0", "b#2", "c#4", "a#1", "b#3"), claimOrder("a", "a", "b", "b", "c"));
    }

    @Test
    void documentsWithoutAKeyShareOneTurn() {
        assertEquals(List.of("null#0", "a#3", "null#1", "a#4", "null#2"), claimOrder(null, null, null, "a", "a"));
    }

    @Test
    void theOwnersRoundsAreUnchanged() {
        final List<FairnessKeys.Queued> pending = List.of(new FairnessKeys.Queued("a1", "a", 2),
                new FairnessKeys.Queued("a2", "a", 5), new FairnessKeys.Queued("a3", "a", 9));
        final FairnessKeys.Plan plan = FairnessKeys.plan(pending, "b", 12);
        assertEquals(5, plan.round(), "the new key's document takes the owner's second round");
        assertEquals(Map.of("a2", 9L, "a3", 12L), plan.reassigned(), "the others move up into the owner's later rounds");
    }

    @Test
    void validKeys() {
        assertTrue(FairnessKeys.isValid("reviewer-42"));
        assertTrue(FairnessKeys.isValid("group:7.a_b"));
        assertTrue(FairnessKeys.isValid("6650c0ffee0123456789abcd"));
        assertTrue(FairnessKeys.isValid("x".repeat(FairnessKeys.MAX_LENGTH)));
        assertFalse(FairnessKeys.isValid(null));
        assertFalse(FairnessKeys.isValid(""));
        assertFalse(FairnessKeys.isValid("x".repeat(FairnessKeys.MAX_LENGTH + 1)));
        assertFalse(FairnessKeys.isValid("jane doe"));
        assertFalse(FairnessKeys.isValid("jane@example.com"));
        assertFalse(FairnessKeys.isValid("key\n"));
    }

}
