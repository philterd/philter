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

import ai.philterd.phileas.policy.PolicyEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Which environment variables a policy may name, as {@code env:NAME} for a crypto or FPE key. Any user can write a
 * policy, and Philter reads a variable a policy names, so Philter always restricts them to the variables in
 * {@code POLICY_ALLOWED_ENV_KEYS}. With none listed, a policy may name no variable. Every policy author can use every
 * listed variable: the list is per deployment, not per user.
 *
 * @param names The variables a policy may name.
 */
public record PolicyEnvironmentSettings(List<String> names) {

    /** Philter's own secrets, which a policy may never name. */
    static final Set<String> PHILTER_SECRETS = Set.of("PHILTER_ENCRYPTION_KEY", "PHILTER_BOOTSTRAP_API_KEY",
            "CACHE_PASSWORD", "MONGODB_CONNECTION_STRING", "PHEYE_BEARER_TOKEN", "SSL_KEYSTORE_PASSWORD",
            "SSL_TRUSTSTORE_PASSWORD");

    /** The Phileas property these settings set, which Phileas would otherwise read ahead of them. */
    static final String PHILEAS_PROPERTY = "policy.env.allowed";

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Reads and checks the settings.
     * @param environment Reads an environment variable, such as {@code System::getenv}.
     * @return The settings.
     * @throws IllegalStateException if a name is not a valid variable name or is one of Philter's own secrets, so a
     * mistake stops Philter rather than silently changing what is allowed.
     */
    public static PolicyEnvironmentSettings fromEnvironment(final Function<String, String> environment) {

        final List<String> names = new ArrayList<>();
        final String listed = environment.apply("POLICY_ALLOWED_ENV_KEYS");
        if (listed != null) {
            for (final String name : Arrays.stream(listed.split(",")).map(String::strip).filter(n -> !n.isEmpty()).toList()) {
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalStateException("POLICY_ALLOWED_ENV_KEYS names '" + name
                            + "', which is not an environment variable name.");
                }
                if (PHILTER_SECRETS.contains(name)) {
                    throw new IllegalStateException("POLICY_ALLOWED_ENV_KEYS names " + name
                            + ", which holds a Philter secret that a policy may not use.");
                }
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }

        return new PolicyEnvironmentSettings(List.copyOf(names));

    }

    /**
     * Refuses the Phileas property set directly as an environment variable or system property, which would otherwise
     * be how Phileas starts out before Philter restricts it.
     * @param environment Reads an environment variable, such as {@code System::getenv}.
     * @param systemProperties Reads a system property, such as {@code System::getProperty}.
     * @throws IllegalStateException if it is set.
     */
    public static void refuseDirectPhileasSetting(final Function<String, String> environment,
                                                  final Function<String, String> systemProperties) {
        if (environment.apply(PHILEAS_PROPERTY) != null || systemProperties.apply(PHILEAS_PROPERTY) != null) {
            throw new IllegalStateException(PHILEAS_PROPERTY + " is set directly, which would override how Philter limits "
                    + "the environment variables a policy may name. Remove it and use POLICY_ALLOWED_ENV_KEYS.");
        }
    }

    /** Restricts Phileas, for the whole process, to these variables. */
    public void apply() {
        PolicyEnvironment.restrictTo(names);
    }

}
