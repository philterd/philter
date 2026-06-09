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

import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.filtering.RedactionOutcome;
import ai.philterd.philter.services.filtering.RedactionService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.nio.charset.StandardCharsets;

@Tag(name = "Explain", description = "Redact text and return a detailed explanation of what was redacted and why.")
@Controller
public class ExplainApiController extends AbstractApiController {

    private final RedactionService redactionService;
    private final Gson gson;

    @Autowired
    public ExplainApiController(final RedactionService redactionService, final ApiKeyDataService apiKeyDataService,
                                final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.redactionService = redactionService;
        this.gson = gson;
    }

    @Operation(summary = "Redact text and explain the redactions.",
            description = "Redacts the plain-text request body using the given policy and context, returning the "
                    + "filtered text together with an explanation of every span that was redacted (type, position, "
                    + "and applied filter). Use the c query parameter to select a context and p to select a policy.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "401")
    })
    @RequestMapping(value = "/api/explain", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public @ResponseBody ResponseEntity<String> explainTextPlainAsApplicationJson(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestBody String body) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final RedactionOutcome outcome = redactionService.filter(policyName, userId, context, body.getBytes(StandardCharsets.UTF_8), MimeType.TEXT_PLAIN);
        final TextFilterResult textFilterResult = (TextFilterResult) outcome.result();

        // Serialize the full filter result (filteredText, context, explanation, ...) to preserve the
        // 3.4.0 /api/explain response shape. Returning only the Explanation in 4.0.0 broke clients
        // (for example PhilterScope) that read filteredText and the nested explanation. The applied
        // policy name and version are added as top-level fields so callers see which policy governed
        // the request without a second call.
        final JsonObject json = gson.toJsonTree(textFilterResult).getAsJsonObject();
        json.addProperty("policyName", outcome.appliedPolicy().name());
        json.addProperty("policyVersion", outcome.appliedPolicy().version());

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .body(gson.toJson(json));

    }

}
