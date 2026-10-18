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
package ai.philterd.philter.api.controllers;

import ai.philterd.philter.api.exceptions.NotFoundException;
import ai.philterd.philter.api.responses.GetModelsResponse;
import ai.philterd.philter.api.responses.ModelResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.models.PhEyeModels;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/** Lists the local PhEye models in PHEYE_MODELS_DIRECTORY, which a policy names in a ph-eye filter's modelPath. */
@Tag(name = "Models", description = "The local PhEye models a policy can name.")
@Controller
public class ModelsApiController extends AbstractApiController {

    private final PhEyeModels models;

    public ModelsApiController(final ApiKeyDataService apiKeyDataService, final ApiKeyCache apiKeyCache) {
        super(apiKeyDataService, apiKeyCache);
        this.models = PhEyeModels.shared();
    }

    @Operation(summary = "List the local PhEye models.",
            description = "Returns the models in the deployment's models directory (PHEYE_MODELS_DIRECTORY), ordered "
                    + "by name. A ph-eye filter uses one by giving its name as phEyeConfiguration.modelPath; the model "
                    + "then runs inside Philter. The directory is read on each request, so a model an administrator "
                    + "adds is listed without a restart. The models are the deployment's, the same for every user.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Every model, which may be none.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetModelsResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized.")
    })
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/models", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<GetModelsResponse> getModels(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {

        requireApiKey(authorizationHeader);

        return ResponseEntity.ok(new GetModelsResponse(models.list().stream().map(ModelResponse::new).toList()));

    }

    @Operation(summary = "Get a local PhEye model.",
            description = "Returns one model in the deployment's models directory by name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The model.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ModelResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "There is no model with the given name.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.POLICIES_READ)
    @RequestMapping(value = "/api/models/{modelName}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<ModelResponse> getModel(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable(name = "modelName") String modelName) {

        requireApiKey(authorizationHeader);

        return ResponseEntity.ok(new ModelResponse(models.find(modelName).orElseThrow(NotFoundException::new)));

    }

}
