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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** Warns when a policy is stored with an encryption key written into it, whatever the storage. */
public final class PolicyKeyWarnings {

    // Logged under the policy service, as before, so log configuration that names it keeps working.
    private static final Logger LOGGER = LogManager.getLogger(PolicyDataService.class);

    /** The policy fields that may hold a key, each optional and each able to name an environment variable. */
    private static final List<String> KEY_BEARING_SECTIONS = List.of("crypto", "fpe");

    /**
     * Warns when a policy is saved with an encryption key written into it. Policies and their version
     * snapshots are not encrypted at rest, so a literal key is stored beside the values it encrypted
     * and database access alone undoes the redaction. Prefixing the value with {@code env:} reads it
     * from the environment instead and stores only the variable's name.
     */
    public static void warnAboutLiteralKeys(final String policyName, final String policyJson) {

        for (final String section : keySectionsHoldingALiteralKey(policyJson)) {
            LOGGER.warn("Policy '{}' stores its {} key in the policy itself. Policies are not encrypted "
                            + "at rest, so that key is readable by anyone who can read the database, and "
                            + "the values it encrypted are stored there too. Use \"env:VARIABLE_NAME\" to "
                            + "read it from the environment instead.",
                    policyName, section);
        }

    }

    /** The key-bearing sections of this policy whose key is a literal rather than an {@code env:} reference. */
    static List<String> keySectionsHoldingALiteralKey(final String policyJson) {

        final List<String> found = new ArrayList<>();

        try {

            final JsonObject policyObject = new Gson().fromJson(policyJson, JsonObject.class);
            if (policyObject == null) {
                return found;
            }

            for (final String section : KEY_BEARING_SECTIONS) {
                if (!policyObject.has(section) || !policyObject.get(section).isJsonObject()) {
                    continue;
                }
                final JsonObject values = policyObject.getAsJsonObject(section);
                for (final String field : List.of("key", "tweak")) {
                    if (values.has(field) && values.get(field).isJsonPrimitive()) {
                        final String value = values.get(field).getAsString();
                        if (value != null && !value.isBlank() && !value.startsWith("env:")) {
                            found.add(section);
                            break;
                        }
                    }
                }
            }

        } catch (final RuntimeException notReadable) {
            // Validation reports malformed policies; this check simply has nothing to say about them.
            return found;
        }

        return found;

    }

    private PolicyKeyWarnings() {
    }

}
