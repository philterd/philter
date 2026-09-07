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

import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.phileas.policy.FPE;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Ignored;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves a stored redaction policy into a ready-to-use Phileas {@link Policy}.
 * <p>
 * Policies are stored and authored in the native Phileas policy format, so resolving is just
 * deserialization plus two pieces of Philter-specific plumbing that the native format does not
 * carry on its own:
 * <ul>
 *   <li><b>Custom list references</b> &mdash; terms of the form {@code list:my-list} in the policy's
 *       ignored terms and custom dictionaries are expanded into the items of the named custom list.</li>
 *   <li><b>Managed FPE key fallback</b> &mdash; if the policy does not supply its own {@code fpe}
 *       object, the user's stable managed FPE key (and derived tweak) is injected so that the
 *       {@code FPE_ENCRYPT_REPLACE} strategy works with zero configuration.</li>
 * </ul>
 */
public class PolicyResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyResolver.class);

    private static final String CUSTOM_LIST_PREFIX = "list:";

    private final Gson gson;
    private final CustomListDataService customListService;

    public PolicyResolver(final Gson gson, final CustomListDataService customListService) {
        this.gson = gson;
        this.customListService = customListService;
    }

    /**
     * Deserializes the stored native policy JSON and applies Philter-specific resolution.
     * @param policyJson The stored native Phileas policy JSON.
     * @param userId The id of the user the policy belongs to (used to resolve custom lists).
     * @param fpeKey The user's managed FPE key, injected when the policy supplies no {@code fpe} object.
     * @param fpeTweak The tweak derived from the managed FPE key.
     * @return The resolved Phileas {@link Policy}.
     */
    public Policy resolve(final String policyJson, final ObjectId userId, final String fpeKey, final String fpeTweak) {

        final Policy policy = gson.fromJson(policyJson, Policy.class);

        // Ensure the ignored list is mutable and non-null so callers can append global ignore terms.
        if (policy.getIgnored() == null) {
            policy.setIgnored(new ArrayList<>());
        }

        final Identifiers identifiers = policy.getIdentifiers();

        // First pass: collect every distinct custom-list name referenced anywhere in the policy so they
        // can be fetched in a single query instead of one query per reference.
        final Set<String> referencedListNames = new HashSet<>();
        for (final Ignored ignored : policy.getIgnored()) {
            collectListNames(ignored.getTerms(), referencedListNames);
        }
        if (identifiers != null && identifiers.getCustomDictionaries() != null) {
            for (final CustomDictionary customDictionary : identifiers.getCustomDictionaries()) {
                collectListNames(customDictionary.getTerms(), referencedListNames);
            }
        }

        // Fetch all referenced lists at once (name -> items).
        final Map<String, List<String>> listItems =
                (customListService != null && userId != null && !referencedListNames.isEmpty())
                        ? customListService.findItemsByNames(userId, referencedListNames)
                        : Collections.emptyMap();

        // Validate every dependency before changing the policy. An empty list is valid;
        // an absent list (including one belonging to another user) must not reduce coverage.
        final Set<String> missing = new TreeSet<>();
        for (final String name : referencedListNames) {
            if (listItems.get(name) == null) {
                missing.add(name);
            }
        }
        if (!missing.isEmpty()) {
            throw new PolicyResolutionException("Policy references unavailable custom lists: "
                    + String.join(", ", missing) + ".");
        }

        final ExpansionBudget budget = new ExpansionBudget();

        // Second pass: expand the references using the prefetched items (no further queries).
        for (final Ignored ignored : policy.getIgnored()) {
            ignored.setTerms(expandCustomListReferences(ignored.getTerms(), listItems, budget));
        }
        if (identifiers != null && identifiers.getCustomDictionaries() != null) {
            for (final CustomDictionary customDictionary : identifiers.getCustomDictionaries()) {
                customDictionary.setTerms(expandCustomListReferences(customDictionary.getTerms(), listItems, budget));
            }
        }

        // Inject the user's managed FPE key as a fallback when the policy does not supply its own.
        // A policy that provides an fpe object keeps full control of its key and tweak.
        if (policy.getFpe() == null && fpeKey != null && !fpeKey.isBlank()) {
            policy.setFpe(new FPE(fpeKey, fpeTweak));
        }

        EffectiveConfigurationLimits.requireSize(gson.toJson(policy));
        return policy;

    }

    /** Adds the names of any {@code list:<name>} references in the given terms to {@code out}. */
    private static void collectListNames(final List<String> terms, final Set<String> out) {
        if (terms == null) {
            return;
        }
        for (final String term : terms) {
            if (term != null && term.startsWith(CUSTOM_LIST_PREFIX)) {
                final String name = term.substring(CUSTOM_LIST_PREFIX.length());
                if (name.isBlank()) {
                    throw new PolicyResolutionException("A custom list reference must include a list name.");
                }
                out.add(name);
            }
        }
    }

    /**
     * Replaces any {@code list:<name>} references in the given terms with the items from the prefetched
     * {@code listItems} map, leaving plain terms unchanged. References must have been validated before expansion.
     */
    private List<String> expandCustomListReferences(final List<String> terms, final Map<String, List<String>> listItems,
                                                    final ExpansionBudget budget) {

        if (terms == null || terms.isEmpty()) {
            return terms;
        }

        final List<String> resolvedTerms = new ArrayList<>();

        for (final String term : terms) {
            if (term != null && term.startsWith(CUSTOM_LIST_PREFIX)) {
                final String listName = term.substring(CUSTOM_LIST_PREFIX.length());
                final List<String> items = listItems.get(listName);
                LOGGER.info("Resolved custom list reference '{}' to {} items", term, items.size());
                for (final String item : items) {
                    budget.add(item);
                    resolvedTerms.add(item);
                }
            } else {
                budget.add(term);
                resolvedTerms.add(term);
            }
        }

        return resolvedTerms;

    }

    /** Shared across ignored terms and every dictionary; charge JSON escaping and separators too. */
    private final class ExpansionBudget {
        private long bytes;
        void add(final String term) {
            bytes += gson.toJson(term).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
            if (bytes > EffectiveConfigurationLimits.MAX_BYTES) {
                throw EffectiveConfigurationLimits.exceeded();
            }
        }
    }

}
