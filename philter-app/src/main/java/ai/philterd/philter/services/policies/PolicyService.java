package ai.philterd.philter.services.policies;

import ai.philterd.phileas.policy.Policy;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface PolicyService {

    /**
     * Gets the names of all policies from
     * the backend store directly by-passing the cache.
     * @return A list of policy names.
     * @throws IOException Thrown if the policy names cannot be read.
     */
    List<String> get() throws IOException;
    /**
     * Gets the content of a policy.
     * @param policyName The name of the policy.
     * @return The policy.
     * @throws IOException Thrown if a policy cannot be read.
     */
    Policy get(String policyName) throws IOException;

    /**
     * Get the names and content of all policies.
     * @return A map of policy names to policy content.
     * @throws IOException Thrown if the policies cannot be read.
     */
    Map<String, Policy> getAll() throws IOException;

    /**
     * Saves a policy.
     * @param policy The content of the policy as JSON.
     * @throws IOException Thrown if the policy cannot be saved.
     */
    void save(Policy policy) throws IOException;

    /**
     * Deletes a policy.
     * @param policyName The name of the policy to delete.
     * @throws IOException Thrown if the policy cannot be deleted.
     */
    void delete(String policyName) throws IOException;

}
