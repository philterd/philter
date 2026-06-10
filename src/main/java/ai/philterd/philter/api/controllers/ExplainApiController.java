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
import ai.philterd.philter.services.signing.SigningService;
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
import java.util.UUID;

@Tag(name = "Explain", description = "Redact text and return a detailed explanation of what was redacted and why.")
@Controller
public class ExplainApiController extends AbstractApiController {

    private final RedactionService redactionService;
    private final Gson gson;
    private final SigningService signingService;

    @Autowired
    public ExplainApiController(final RedactionService redactionService, final ApiKeyDataService apiKeyDataService,
                                final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache,
                                final Gson gson, final SigningService signingService) {
        super(apiKeyDataService, apiKeyCache);
        this.redactionService = redactionService;
        this.gson = gson;
        this.signingService = signingService;
    }

    @Operation(summary = "Redact text and explain the redactions.",
            description = "Redacts the plain-text request body using the given policy and context, returning the "
                    + "filtered text together with an explanation of every span that was redacted (type, position, "
                    + "and applied filter). Use the c query parameter to select a context and p to select a policy. "
                    + "When output signing is enabled in Admin Settings, the response includes an "
                    + "`X-Philter-Signature` header containing an ES256 JWT that binds the SHA-256 hash of the "
                    + "response body, the applied policy name and version, a per-response UUID, and an issue "
                    + "timestamp. Verify the signature using the public key from `GET /api/signing-key`.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Filtered text with explanation. Includes `X-Philter-Signature` JWT header when output signing is enabled."),
            @ApiResponse(responseCode = "401", description = "Unauthorized."),
            @ApiResponse(responseCode = "500", description = "Signing is enabled but the signing operation failed.")
    })
    @RequestMapping(value = "/api/explain", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public @ResponseBody ResponseEntity<String> explainTextPlainAsApplicationJson(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "filename", required = false) String filename,
            @RequestBody String body) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final RedactionOutcome outcome = redactionService.filter(policyName, userId, context, body.getBytes(StandardCharsets.UTF_8), MimeType.TEXT_PLAIN, filename);
        final TextFilterResult textFilterResult = (TextFilterResult) outcome.result();

        // Serialize the full filter result (filteredText, context, explanation, ...) to preserve the
        // 3.4.0 /api/explain response shape. Returning only the Explanation in 4.0.0 broke clients
        // (for example PhilterScope) that read filteredText and the nested explanation. The applied
        // policy name, version, and content hash are added as top-level fields so callers see which
        // policy governed the request, and can verify the exact policy content, without a second call.
        final JsonObject json = gson.toJsonTree(textFilterResult).getAsJsonObject();
        json.addProperty("policyName", outcome.appliedPolicy().name());
        json.addProperty("policyVersion", outcome.appliedPolicy().version());
        json.addProperty("policyContentHash", outcome.appliedPolicy().contentHash());

        final String documentId = UUID.randomUUID().toString();
        final String responseBody = gson.toJson(json);
        final HttpHeaders headers = new HttpHeaders();
        headers.set(FilterApiController.DOCUMENT_ID_HEADER, documentId);
        if (signingService.isSigningEnabled()) {
            headers.set(SigningService.SIGNATURE_HEADER, signingService.sign(
                    responseBody,
                    outcome.appliedPolicy().name(),
                    outcome.appliedPolicy().version(),
                    documentId));
        }

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers)
                .body(responseBody);

    }

}
