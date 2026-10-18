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
import ai.philterd.philter.services.models.PhEyeModels;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps a policy from directing Philter's requests or credentials. Any user can write a policy, so the services a
 * policy reaches are the operator's decision: a PhEye {@code endpoint} must be the deployment's own
 * ({@code PHEYE_ENDPOINT}) or one listed in {@code PHEYE_ALLOWED_ENDPOINTS}; a policy may not set a PhEye
 * {@code bearerToken}; a PhEye {@code modelPath} must be a model in {@code PHEYE_MODELS_DIRECTORY} or listed in
 * {@code PHEYE_ALLOWED_MODEL_PATHS}; and a
 * generator's {@code endpoint} must be listed in {@code GENERATOR_ALLOWED_ENDPOINTS}. Without these, a policy could
 * send the text being redacted, or the deployment's PhEye token, to a server of its author's choosing.
 */
public final class PolicyResourceCheck {

    private final Set<String> pheyeEndpoints;
    private final Set<String> pheyeModelPaths;
    private final PhEyeModels models;
    private final Set<String> generatorEndpoints;

    /**
     * @param environment Reads an environment variable, such as {@code System::getenv}.
     */
    public PolicyResourceCheck(final Function<String, String> environment) {
        // The deployment's own endpoint, as Phileas resolves it when a policy names none.
        this.pheyeEndpoints = union(Set.of(normalize(new PhEyeConfiguration().getEndpoint())),
                list(environment.apply("PHEYE_ALLOWED_ENDPOINTS")));
        this.pheyeModelPaths = list(environment.apply("PHEYE_ALLOWED_MODEL_PATHS"));
        this.models = PhEyeModels.fromEnvironment(environment);
        this.generatorEndpoints = list(environment.apply("GENERATOR_ALLOWED_ENDPOINTS"));
    }

    /**
     * Why the policy reaches a service or file the deployment does not allow, or null when it does not.
     * @param policy The policy as JSON.
     * @return The reason, or null.
     */
    public String refusal(final JsonObject policy) {

        final String pheye = refusalInPhEyeConfigurations(policy);
        if (pheye != null) {
            return pheye;
        }

        if (policy.get("generators") instanceof JsonObject generators) {
            for (final Map.Entry<String, JsonElement> generator : generators.entrySet()) {
                if (generator.getValue() instanceof JsonObject definition && definition.has("endpoint")
                        && !definition.get("endpoint").isJsonNull()
                        && !generatorEndpoints.contains(normalize(definition.get("endpoint").getAsString()))) {
                    return "The endpoint of generator '" + generator.getKey() + "' is not one this deployment allows. "
                            + "An administrator can allow it with GENERATOR_ALLOWED_ENDPOINTS.";
                }
            }
        }

        return null;

    }

    /** Checks every phEyeConfiguration object in the policy, wherever it is. */
    private String refusalInPhEyeConfigurations(final JsonElement element) {

        if (element instanceof JsonObject object) {
            for (final Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if ("phEyeConfiguration".equals(entry.getKey()) && entry.getValue() instanceof JsonObject configuration) {
                    final String reason = refusalOfPhEye(configuration);
                    if (reason != null) {
                        return reason;
                    }
                } else {
                    final String reason = refusalInPhEyeConfigurations(entry.getValue());
                    if (reason != null) {
                        return reason;
                    }
                }
            }
        } else if (element != null && element.isJsonArray()) {
            for (final JsonElement item : element.getAsJsonArray()) {
                final String reason = refusalInPhEyeConfigurations(item);
                if (reason != null) {
                    return reason;
                }
            }
        }

        return null;

    }

    private String refusalOfPhEye(final JsonObject configuration) {
        if (isSet(configuration, "endpoint") && !pheyeEndpoints.contains(normalize(configuration.get("endpoint").getAsString()))) {
            return "The PhEye endpoint is not one this deployment allows. Leave endpoint out to use the deployment's "
                    + "PhEye service, or an administrator can allow it with PHEYE_ALLOWED_ENDPOINTS.";
        }
        if (isSet(configuration, "bearerToken")) {
            return "A policy cannot set a PhEye bearerToken. The deployment's PHEYE_BEARER_TOKEN is used for its "
                    + "PhEye service.";
        }
        if (isSet(configuration, "modelPath")) {
            final String modelPath = configuration.get("modelPath").getAsString();
            if (!pheyeModelPaths.contains(modelPath) && !models.contains(modelPath)) {
                return "The PhEye modelPath is not a model this deployment has. Name one that GET /api/models "
                        + "lists, or an administrator can allow the path with PHEYE_ALLOWED_MODEL_PATHS.";
            }
        }
        return null;
    }

    private static boolean isSet(final JsonObject object, final String field) {
        return object.has(field) && !object.get(field).isJsonNull();
    }

    /** An endpoint compared without a trailing slash, the way Phileas compares it. */
    private static String normalize(final String endpoint) {
        return endpoint == null ? "" : endpoint.strip().replaceAll("/+$", "");
    }

    private static Set<String> list(final String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(s -> s.startsWith("http") ? normalize(s) : s).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> union(final Set<String> a, final Set<String> b) {
        return Stream.concat(a.stream(), b.stream()).collect(Collectors.toUnmodifiableSet());
    }

}
