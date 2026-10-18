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
package ai.philterd.philter.api.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** The local PhEye models a policy can name. */
public class GetModelsResponse {

    private final List<ModelResponse> models;

    public GetModelsResponse(final List<ModelResponse> models) {
        this.models = models;
    }

    @Schema(description = "Every model, ordered by name. Empty when the deployment has none.")
    public List<ModelResponse> getModels() { return models; }

}
