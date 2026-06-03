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

import ai.philterd.philter.data.entities.CustomListEntity;
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
import java.util.List;

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

        // Expand custom list references in ignored terms.
        for (final Ignored ignored : policy.getIgnored()) {
            ignored.setTerms(resolveCustomListReferences(ignored.getTerms(), userId));
        }

        // Expand custom list references in custom dictionaries.
        final Identifiers identifiers = policy.getIdentifiers();
        if (identifiers != null && identifiers.getCustomDictionaries() != null) {
            for (final CustomDictionary customDictionary : identifiers.getCustomDictionaries()) {
                customDictionary.setTerms(resolveCustomListReferences(customDictionary.getTerms(), userId));
            }
        }

        // Inject the user's managed FPE key as a fallback when the policy does not supply its own.
        // A policy that provides an fpe object keeps full control of its key and tweak.
        if (policy.getFpe() == null && fpeKey != null && !fpeKey.isBlank()) {
            policy.setFpe(new FPE(fpeKey, fpeTweak));
        }

        return policy;

    }

    /**
     * Replaces any {@code list:<name>} references in the given terms with the items of the named
     * custom list, leaving plain terms unchanged. Never returns null.
     */
    private List<String> resolveCustomListReferences(final List<String> terms, final ObjectId userId) {

        if (terms == null || terms.isEmpty()) {
            return terms;
        }

        if (customListService == null || userId == null) {
            return terms;
        }

        final List<String> resolvedTerms = new ArrayList<>();

        for (final String term : terms) {
            if (term != null && term.startsWith(CUSTOM_LIST_PREFIX)) {
                final String listName = term.substring(CUSTOM_LIST_PREFIX.length());
                final CustomListEntity customList = customListService.findOneByName(listName, userId);
                if (customList != null && customList.getItems() != null) {
                    LOGGER.info("Resolved custom list reference '{}' to {} items", term, customList.getItems().size());
                    resolvedTerms.addAll(customList.getItems());
                } else {
                    LOGGER.warn("Custom list '{}' not found for user. Reference will be ignored.", listName);
                }
            } else {
                resolvedTerms.add(term);
            }
        }

        return resolvedTerms;

    }

}
