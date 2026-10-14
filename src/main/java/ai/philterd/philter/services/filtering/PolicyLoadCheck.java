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

import ai.philterd.phileas.policy.InvalidPolicyConditionException;
import ai.philterd.phileas.policy.InvalidPolicyPatternException;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.services.FilterPolicyLoader;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds a policy that Phileas refuses to load, by building its filters as redaction does, or that reaches a service
 * or file the deployment does not allow (see {@link PolicyResourceCheck}). Phileas checks a
 * policy when it builds the filters, not when the JSON is read, so without this a policy it refuses would be
 * saved and then fail every redaction. Only Phileas's refusals of the policy are reported: an unparseable or
 * unsupported strategy condition, a policy regular expression that does not compile or fails its probe, and an
 * invalid value such as an unknown {@code sensitivity}. Any other failure is left for redaction to report.
 */
public final class PolicyLoadCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyLoadCheck.class);

    /** How many loadable policies are remembered before the set is cleared, bounding its memory. */
    private static final int MAX_REMEMBERED = 10_000;

    private static final PolicyLoadCheck SHARED = new PolicyLoadCheck();

    private static final Gson GSON = new Gson();

    private final FilterPolicyLoader loader;
    private final PolicyResourceCheck resources = new PolicyResourceCheck(System::getenv);
    private final Set<String> loadable = ConcurrentHashMap.newKeySet();

    private PolicyLoadCheck() {
        // The same Phileas configuration redaction uses without span disambiguation, which does not change
        // how a policy is loaded.
        this.loader = new FilterPolicyLoader(new PhileasConfigurationCache().get(false), new SecureRandom(), null);
    }

    /** The check shared by policy validation and redaction. */
    public static PolicyLoadCheck shared() {
        return SHARED;
    }

    /**
     * Why Phileas refuses to load this policy, or null when it loads. A policy that loads is remembered by its
     * content, so it is built at most once.
     * @param policy The policy, as Phileas reads it.
     * @return Phileas's reason, which never repeats a condition's text, or null.
     */
    public String refusal(final Policy policy) {

        final String key = policy.getCacheKey();
        if (loadable.contains(key)) {
            return null;
        }

        // Before the filters are built: building a PhEye filter with a modelPath loads that model.
        final String resource = resources.refusal(GSON.toJsonTree(policy).getAsJsonObject());
        if (resource != null) {
            return resource;
        }

        try {
            loader.getFiltersForPolicy(policy, new HashMap<>());
        } catch (final InvalidPolicyConditionException | InvalidPolicyPatternException | IllegalArgumentException ex) {
            return ex.getMessage();
        } catch (final Exception ex) {
            // Not a refusal of the policy itself, such as a file it names being unreadable here. Redaction
            // reports it if it happens there too.
            LOGGER.debug("Building the policy's filters failed for a reason other than the policy: {}", ex.toString());
            return null;
        }

        if (loadable.size() >= MAX_REMEMBERED) {
            loadable.clear();
        }
        loadable.add(key);
        return null;

    }

}
