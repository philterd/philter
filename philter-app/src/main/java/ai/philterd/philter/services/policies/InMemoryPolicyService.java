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

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InMemoryPolicyService implements PolicyService {

    private final Map<String, Policy> policies;

    public InMemoryPolicyService() {
        this.policies = new HashMap<>();
    }

    @Override
    public List<String> get() throws IOException {
        return policies.keySet().stream().toList();
    }

    @Override
    public Policy get(String policyName) throws IOException {
        return policies.get(policyName);
    }

    @Override
    public Map<String, Policy> getAll() {
        return policies;
    }

    @Override
    public void save(Policy policy) {
        policies.put(policy.getName(), policy);
    }

    @Override
    public void delete(String policyName) {
        policies.remove(policyName);
    }

}