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

import ai.philterd.phileas.policy.Policy;
import ai.philterd.philter.data.entities.PolicyEntity;
import com.google.gson.Gson;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Loads the built-in managed policies from native Phileas policy JSON files in the resources
 * directory. The native policy format does not carry a name or description, so those are defined
 * here alongside each file.
 */
public class ManagedPolicyLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagedPolicyLoader.class);
    private static final String MANAGED_POLICIES_PATH = "/managed-policies/";

    /**
     * A built-in managed policy: the resource file holding its native policy JSON plus the name and
     * description used to present it.
     */
    private record ManagedPolicy(String fileName, String name, String description) {}

    private static final List<ManagedPolicy> MANAGED_POLICIES = List.of(
            new ManagedPolicy("common-pii.json", "managed_common_pii",
                    "Common PII including names, emails, phone numbers, and SSNs"),
            new ManagedPolicy("healthcare-phi.json", "managed_healthcare_phi",
                    "Healthcare PHI including names, dates, addresses, and medical identifiers"),
            new ManagedPolicy("financial-pii.json", "managed_financial_pii",
                    "Financial PII including credit cards, bank routing numbers, and Bitcoin addresses")
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

        for (final ManagedPolicy managedPolicy : MANAGED_POLICIES) {

            try {

                final String resourcePath = MANAGED_POLICIES_PATH + managedPolicy.fileName();
                final InputStream inputStream = getClass().getResourceAsStream(resourcePath);

                if (inputStream == null) {
                    LOGGER.warn("Managed policy file not found: {}", resourcePath);
                    continue;
                }

                final String policyJson = IOUtils.toString(inputStream, StandardCharsets.UTF_8);

                // Parse to confirm the resource is a valid native Phileas policy; store the raw JSON.
                gson.fromJson(policyJson, Policy.class);

                final PolicyEntity policyEntity = new PolicyEntity();
                policyEntity.setPolicy(policyJson);
                policyEntity.setName(managedPolicy.name());
                policyEntity.setDescription(managedPolicy.description());
                policyEntity.setManaged(true);
                policyEntity.setUserId(null); // Managed policies have no owner
                policyEntity.setCreatedTimestamp(new Date());
                policyEntity.setLastUpdatedTimestamp(new Date());
                policyEntity.setRevision(1);
                policyEntity.setShared(false);

                managedPolicies.add(policyEntity);

                LOGGER.info("Loaded managed policy: {}", managedPolicy.name());

            } catch (final Exception ex) {
                LOGGER.error("Error loading managed policy from file: {}", managedPolicy.fileName(), ex);
            }

        }

        LOGGER.info("Loaded {} managed policies", managedPolicies.size());
        return managedPolicies;

    }

}
