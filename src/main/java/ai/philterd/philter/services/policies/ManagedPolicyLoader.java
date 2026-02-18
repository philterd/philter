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

import ai.philterd.philter.data.entities.PolicyEntity;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Utility class to load managed policies from JSON files in the resources directory.
 */
public class ManagedPolicyLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedPolicyLoader.class);
    private static final String MANAGED_POLICIES_PATH = "/managed-policies/";
    private static final List<String> MANAGED_POLICY_FILES = List.of(
            "common-pii.json",
            "healthcare-phi.json",
            "financial-pii.json"
    );

    private final Gson gson;

    public ManagedPolicyLoader(final Gson gson) {
        this.gson = gson;
    }

    /**
     * Load all managed policies from the resources directory.
     * @return A list of PolicyEntity objects representing the managed policies.
     */
    public List<PolicyEntity> loadManagedPolicies() {

        final List<PolicyEntity> managedPolicies = new ArrayList<>();

        for (final String fileName : MANAGED_POLICY_FILES) {

            try {

                final String resourcePath = MANAGED_POLICIES_PATH + fileName;
                final InputStream inputStream = getClass().getResourceAsStream(resourcePath);

                if (inputStream == null) {
                    LOGGER.warn("Managed policy file not found: {}", resourcePath);
                    continue;
                }

                try (final InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {

                    final SimplifiedPolicy simplifiedPolicy = gson.fromJson(reader, SimplifiedPolicy.class);

                    // Convert to JSON string for storage
                    final String policyJson = gson.toJson(simplifiedPolicy);

                    // Create PolicyEntity
                    final PolicyEntity policyEntity = new PolicyEntity();
                    policyEntity.setPolicy(policyJson);
                    policyEntity.setName(simplifiedPolicy.getName());
                    policyEntity.setDescription(simplifiedPolicy.getDescription());
                    policyEntity.setManaged(true);
                    policyEntity.setUserId(null); // Managed policies have no owner
                    policyEntity.setCreatedTimestamp(new Date());
                    policyEntity.setLastUpdatedTimestamp(new Date());
                    policyEntity.setRevision(1);
                    policyEntity.setShared(false);

                    managedPolicies.add(policyEntity);

                    LOGGER.info("Loaded managed policy: {}", simplifiedPolicy.getName());

                }

            } catch (final IOException ex) {
                LOGGER.error("Error loading managed policy from file: {}", fileName, ex);
            } catch (final Exception ex) {
                LOGGER.error("Error parsing managed policy from file: {}", fileName, ex);
            }

        }

        LOGGER.info("Loaded {} managed policies", managedPolicies.size());
        return managedPolicies;

    }

}
