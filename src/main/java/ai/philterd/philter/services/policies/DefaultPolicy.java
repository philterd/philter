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

/**
 * The default redaction policy used as a starting point for new users and the policy editor.
 * Expressed in the native Phileas policy format that Philter parses.
 */
public final class DefaultPolicy {

    private static final String DEFAULT_POLICY_JSON = """
            {
              "identifiers": {
                "person": {
                  "phEyeFilterStrategies": [
                    { "strategy": "REDACT" }
                  ]
                },
                "ssn": {
                  "ssnFilterStrategies": [
                    { "strategy": "REDACT" }
                  ]
                },
                "emailAddress": {
                  "emailAddressFilterStrategies": [
                    { "strategy": "REDACT" }
                  ]
                }
              }
            }
            """;

    private DefaultPolicy() {
        // Access the default policy through the static method.
    }

    /**
     * Returns the default redaction policy as native Phileas policy JSON.
     * @return The default policy JSON.
     */
    public static String json() {
        return DEFAULT_POLICY_JSON;
    }

}
