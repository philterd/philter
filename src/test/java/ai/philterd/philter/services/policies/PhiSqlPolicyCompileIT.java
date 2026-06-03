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
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.PolicyEntity;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.testutil.AbstractMongoIT;
import com.google.gson.Gson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * End-to-end test for issue #199 acceptance criterion 3: a policy compiled from PhiSQL is accepted by
 * Philter's policy API. It compiles PhiSQL to native Phileas JSON with the real compiler, then feeds
 * that JSON to the real {@link PolicyDataService#create} path (validation + persistence, no mocks)
 * against an in-memory MongoDB, and confirms it round-trips as a native Phileas policy.
 */
class PhiSqlPolicyCompileIT extends AbstractMongoIT {

    private final Gson gson = new Gson();
    private final PhiSqlCompileService compiler = new PhiSqlCompileService();
    private PolicyDataService policyDataService;

    @BeforeEach
    void setUpService() {
        policyDataService = new PolicyDataService(mongoClient, mock(AuditEventPublisher.class), gson);
    }

    @Test
    void compiledPhiSqlPolicyIsAcceptedAndStoredByThePolicyApi() {
        final ObjectId userId = new ObjectId();

        // Compile a PhiSQL policy to native Phileas JSON.
        final PhiSqlCompileService.Result result = compiler.compile("POLICY ssn_only;\nREDACT SSN WITH MASK;");
        assertTrue(result.isSuccess(), "the PhiSQL must compile");

        // The compiled native policy is accepted by Philter's policy API (real validation + storage).
        final ServiceResponse response = policyDataService.create(
                "req", userId, result.getPolicyJson(), "desc", "notes", "ssn_only", "source");
        assertTrue(response.isSuccessful(), "the compiled PhiSQL policy must be accepted by the policy API");

        // And it round-trips as a native Phileas policy carrying the expected SSN filter.
        final PolicyEntity stored = policyDataService.findOne("ssn_only", userId);
        assertNotNull(stored, "the compiled policy must be persisted");
        final Policy policy = gson.fromJson(stored.getPolicy(), Policy.class);
        assertNotNull(policy.getIdentifiers(), "the stored policy must deserialize to a native policy");
        assertNotNull(policy.getIdentifiers().getSsn(), "the compiled SSN filter must survive the round-trip");
    }

}
