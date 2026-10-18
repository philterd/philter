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

import ai.philterd.phileas.policy.filters.pheye.PhEyeConfiguration;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyResourceCheckTest {

    private static final String DEFAULT_ENDPOINT = new PhEyeConfiguration().getEndpoint();

    private final PolicyResourceCheck check = new PolicyResourceCheck(Map.of(
            "PHEYE_ALLOWED_ENDPOINTS", "https://pheye-two.internal:5000/, https://pheye-three.internal",
            "PHEYE_ALLOWED_MODEL_PATHS", "/opt/models/gliner",
            "GENERATOR_ALLOWED_ENDPOINTS", "http://ollama.internal:11434")::get);

    private static JsonObject policy(final String json) {
        return new Gson().fromJson(json, JsonObject.class);
    }

    private static String pheye(final String configuration) {
        return "{\"identifiers\":{\"pheyes\":[{\"phEyeConfiguration\":" + configuration + "}]}}";
    }

    @Test
    void aPolicyThatReachesNothingIsAllowed() {
        assertNull(check.refusal(policy("{\"identifiers\":{\"ssn\":{}}}")));
        assertNull(check.refusal(policy("{\"identifiers\":{\"pheyes\":[{\"phEyeFilterStrategies\":[{\"strategy\":\"REDACT\"}]}]}}")));
    }

    @Test
    void theDeploymentsOwnEndpointIsAllowed() {
        final String bare = DEFAULT_ENDPOINT.replaceAll("/+$", "");
        assertNull(check.refusal(policy(pheye("{\"endpoint\":\"" + bare + "\"}"))));
        assertNull(check.refusal(policy(pheye("{\"endpoint\":\"" + bare + "/\"}"))));
    }

    @Test
    void anAllowlistedEndpointIsAllowedWithOrWithoutATrailingSlash() {
        assertNull(check.refusal(policy(pheye("{\"endpoint\":\"https://pheye-two.internal:5000\"}"))));
        assertNull(check.refusal(policy(pheye("{\"endpoint\":\"https://pheye-three.internal/\"}"))));
    }

    @Test
    void anyOtherEndpointIsRefused() {
        final String reason = check.refusal(policy(pheye("{\"endpoint\":\"https://attacker.example\"}")));
        assertTrue(reason.startsWith("The PhEye endpoint is not one this deployment allows."), reason);
        assertTrue(!reason.contains("attacker.example"), "the endpoint is not repeated");
        assertTrue(check.refusal(policy(pheye("{\"endpoint\":\"http://169.254.169.254/latest\"}"))) != null);
    }

    @Test
    void aBearerTokenIsRefused() {
        assertEquals("A policy cannot set a PhEye bearerToken. The deployment's PHEYE_BEARER_TOKEN is used for its PhEye service.",
                check.refusal(policy(pheye("{\"bearerToken\":\"x\"}"))));
    }

    @Test
    void aModelPathMustBeAllowlisted() {
        assertNull(check.refusal(policy(pheye("{\"modelPath\":\"/opt/models/gliner\"}"))));
        assertTrue(check.refusal(policy(pheye("{\"modelPath\":\"/etc\"}"))).startsWith("The PhEye modelPath is not a model"));
    }

    @Test
    void aConfigurationAnywhereInThePolicyIsChecked() {
        assertTrue(check.refusal(policy("{\"identifiers\":{\"person\":{\"phEyeConfiguration\":{\"endpoint\":\"https://attacker.example\"}}}}")) != null);
    }

    @Test
    void aGeneratorEndpointMustBeAllowlisted() {
        assertNull(check.refusal(policy("{\"generators\":{\"names\":{\"type\":\"OLLAMA\",\"endpoint\":\"http://ollama.internal:11434/\"}},\"identifiers\":{}}")));
        assertEquals("The endpoint of generator 'names' is not one this deployment allows. An administrator can allow it with "
                        + "GENERATOR_ALLOWED_ENDPOINTS.",
                check.refusal(policy("{\"generators\":{\"names\":{\"type\":\"OLLAMA\",\"endpoint\":\"http://10.0.0.5:11434\"}},\"identifiers\":{}}")));
        assertNull(check.refusal(policy("{\"generators\":{\"names\":{\"type\":\"OLLAMA\"}},\"identifiers\":{}}")));
    }

    @Test
    void withNoAllowlistsOnlyTheDeploymentsOwnEndpointIsAllowed() {
        final PolicyResourceCheck strict = new PolicyResourceCheck(name -> null);
        assertNull(strict.refusal(policy(pheye("{\"endpoint\":\"" + DEFAULT_ENDPOINT + "\"}"))));
        assertTrue(strict.refusal(policy(pheye("{\"endpoint\":\"https://pheye-two.internal:5000\"}"))) != null);
        assertTrue(strict.refusal(policy(pheye("{\"modelPath\":\"/opt/models/gliner\"}"))) != null);
    }

    @Test
    void aModelInTheModelsDirectoryIsAllowedByNameOrPath(@TempDir final Path models) throws Exception {
        final Path model = Files.createDirectory(models.resolve("pii-en-small"));
        for (final String file : new String[] {"gliner_config.json", "tokenizer.json", "model.onnx"}) {
            Files.writeString(model.resolve(file), "{}");
        }
        final PolicyResourceCheck withModels = new PolicyResourceCheck(
                Map.of("PHEYE_MODELS_DIRECTORY", models.toString())::get);

        assertNull(withModels.refusal(policy(pheye("{\"modelPath\":\"pii-en-small\"}"))));
        assertNull(withModels.refusal(policy(pheye("{\"modelPath\":\"" + model.toRealPath() + "\"}"))));
        assertTrue(withModels.refusal(policy(pheye("{\"modelPath\":\"pii-en-large\"}"))).startsWith(
                "The PhEye modelPath is not a model"));
        assertTrue(withModels.refusal(policy(pheye("{\"modelPath\":\"" + models.toRealPath() + "\"}"))) != null);
    }

}
