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

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Starting points for a new policy, by name, served through the API so a client need not keep its own
 * copy that can fall behind the policy schema. Each is native Phileas policy JSON that Philter accepts.
 */
public final class PolicyTemplates {

    /** The template every new user's default policy is created from. */
    public static final String DEFAULT = "default";

    private static final Map<String, String> TEMPLATES = Map.of(DEFAULT, DefaultPolicy.json());

    private PolicyTemplates() {
    }

    /** The template's policy JSON, or {@code null} when there is no template with that name. */
    public static String find(final String name) {
        return name == null ? null : TEMPLATES.get(name);
    }

    /** The names of every template, in order. */
    public static Set<String> names() {
        return new TreeSet<>(TEMPLATES.keySet());
    }

}
