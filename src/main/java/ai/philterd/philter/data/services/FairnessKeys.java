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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns between the fairness keys of one owner's queued documents. Each owner's documents hold the same fairness
 * rounds they would without keys, so owners share the queue exactly as before; within an owner, the rounds are
 * dealt out so that documents with different keys take turns, and a key's first document waits for one document of
 * each key already queued. Documents without a key share one turn, so an owner who never passes a key is claimed in
 * submission order.
 */
public final class FairnessKeys {

    /** The longest key accepted. */
    public static final int MAX_LENGTH = 64;

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._:-]{1," + MAX_LENGTH + "}");

    private FairnessKeys() {
    }

    /** Whether {@code key} is a fairness key that can be accepted. */
    public static boolean isValid(final String key) {
        return key != null && VALID.matcher(key).matches();
    }

    /** One of an owner's pending documents: its id, fairness key (null for none), and fairness round. */
    public record Queued(String id, String key, long round) {
    }

    /**
     * Where a new document goes: the round it is stored in, and the owner's pending documents whose round changes to
     * make room for it.
     */
    public record Plan(long round, Map<String, Long> reassigned) {
    }

    /**
     * Places a new document with {@code key} among the owner's pending documents.
     *
     * @param pending the owner's pending documents, in the order they would be claimed
     * @param key the new document's fairness key, or null
     * @param round the next round in the owner's share of the queue
     */
    public static Plan plan(final List<Queued> pending, final String key, final long round) {
        // Each key's documents, in claim order, with keys ordered by their first document. null stands for the new one.
        final Map<String, List<Queued>> turns = new LinkedHashMap<>();
        for (final Queued queued : pending) {
            turns.computeIfAbsent(queued.key(), k -> new ArrayList<>()).add(queued);
        }
        turns.computeIfAbsent(key, k -> new ArrayList<>()).add(null);

        final List<Queued> order = new ArrayList<>();
        for (int turn = 0; order.size() <= pending.size(); turn++) {
            for (final List<Queued> documents : turns.values()) {
                if (turn < documents.size()) {
                    order.add(documents.get(turn));
                }
            }
        }

        // The owner's rounds are unchanged; only which document holds each one moves.
        final List<Long> rounds = new ArrayList<>(pending.size() + 1);
        for (final Queued queued : pending) {
            rounds.add(queued.round());
        }
        rounds.add(round);
        rounds.sort(null);

        long placed = round;
        final Map<String, Long> reassigned = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            final Queued queued = order.get(i);
            if (queued == null) {
                placed = rounds.get(i);
            } else if (queued.round() != rounds.get(i)) {
                reassigned.put(queued.id(), rounds.get(i));
            }
        }
        return new Plan(placed, reassigned);
    }

}
