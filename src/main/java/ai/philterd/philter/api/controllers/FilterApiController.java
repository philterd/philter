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

import ai.philterd.phileas.model.filtering.AbstractFilterResult;
import ai.philterd.phileas.model.filtering.BinaryDocumentFilterResult;
import ai.philterd.phileas.model.filtering.MimeType;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.filtering.RedactionService;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Tag(name = "Filter", description = "Redact text and PDF documents.")
@Controller
public class FilterApiController extends AbstractApiController {

    private static final Logger LOGGER = LogManager.getLogger(FilterApiController.class);

    private final RedactionService redactionService;
    private final AuditEventPublisher auditEventPublisher;
    private final PendingDocumentDataService pendingDocumentDataService;
    private final Gson gson;

    @Autowired
    public FilterApiController(final RedactionService redactionService, final PolicyDataService policyDataService, final ApiKeyDataService apiKeyDataService,
                               final AuditEventPublisher auditEventPublisher, final ApiKeyCache apiKeyCache,
                               final PendingDocumentDataService pendingDocumentDataService, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.redactionService = redactionService;
        this.auditEventPublisher = auditEventPublisher;
        this.pendingDocumentDataService = pendingDocumentDataService;
        this.gson = gson;
    }

    @Operation(
            summary = "Filter a PDF and receive a ZIP archive in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted ZIP bytes inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted ZIP bytes (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = "application/zip", consumes = MediaType.APPLICATION_PDF_VALUE)
    public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationZip(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "none") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded binary PDF file to be returned as ZIP. async={}", async);

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        if (async) {
            return enqueueBinary(userId, body, MimeType.APPLICATION_PDF, "application/zip", policyName, context);
        }

        final AbstractFilterResult response = redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF);
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) response;

        return ResponseEntity.status(HttpStatus.OK)
                .body(binaryDocumentFilterResult.getDocument());

    }

    @Operation(
            summary = "Filter a PDF and receive a redacted PDF in return.",
            description = "Asynchronous by default. Returns 202 Accepted with `{\"documentId\":...}` and a `Location: /api/documents/{documentId}` header. " +
                    "Append `?async=false` to receive the redacted PDF bytes inline (200 OK)."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted PDF bytes (synchronous path)."),
            @ApiResponse(responseCode = "202", description = "Job accepted; poll /api/documents/{documentId}/status and download from /api/documents/{documentId}."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = MediaType.APPLICATION_PDF_VALUE, consumes = MediaType.APPLICATION_PDF_VALUE)
    public @ResponseBody ResponseEntity<byte[]> filterApplicationPdfAsApplicationPdf(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "none") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestParam(value = "async", defaultValue = "true") boolean async,
            @RequestBody byte[] body) throws Exception {

        LOGGER.info("Received uploaded binary PDF file to be returned as PDF. async={}", async);

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        if (async) {
            return enqueueBinary(userId, body, MimeType.APPLICATION_PDF, MediaType.APPLICATION_PDF_VALUE, policyName, context);
        }

        final AbstractFilterResult response = redactionService.filter(policyName, userId, context, body, MimeType.APPLICATION_PDF);
        final BinaryDocumentFilterResult binaryDocumentFilterResult = (BinaryDocumentFilterResult) response;

        return ResponseEntity.status(HttpStatus.OK)
                .body(binaryDocumentFilterResult.getDocument());

    }

    @Operation(
            summary = "Filter plain text and receive redacted plain text inline.",
            description = "Synchronous. The `async` parameter does not apply to the text endpoint."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Redacted plain text."),
            @ApiResponse(responseCode = "401", description = "Unauthorized.")
    })
    @RequestMapping(value = "/api/filter", method = RequestMethod.POST, produces = MediaType.TEXT_PLAIN_VALUE, consumes = MediaType.TEXT_PLAIN_VALUE)
    public @ResponseBody ResponseEntity<String> filterTextPlainAsTextPlain(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @RequestParam(value = "c", defaultValue = "none") String context,
            @RequestParam(value = "p", defaultValue = "default") String policyName,
            @RequestBody String body) throws Exception {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final AbstractFilterResult response = redactionService.filter(policyName, userId, context, body.getBytes(StandardCharsets.UTF_8), MimeType.TEXT_PLAIN);
        final TextFilterResult textFilterResult = (TextFilterResult) response;

        return ResponseEntity.status(HttpStatus.OK)
                .body(textFilterResult.getFilteredText());

    }

    private ResponseEntity<byte[]> enqueueBinary(final ObjectId userId, final byte[] body, final MimeType inputMimeType,
                                                 final String outputMimeType, final String policyName, final String contextName) {

        final String documentId = UUID.randomUUID().toString();

        final PendingDocumentEntity entity = new PendingDocumentEntity();
        entity.setUserId(userId);
        entity.setDocumentId(documentId);
        entity.setInputMimeType(inputMimeType.name());
        entity.setOutputMimeType(outputMimeType);
        entity.setPolicyName(policyName);
        entity.setContextName(contextName);
        entity.setStatus(PendingDocumentEntity.STATUS_PENDING);
        entity.setInput(body);
        entity.setSubmittedAt(new Date());

        pendingDocumentDataService.save(entity);

        // Audit that a document was submitted for asynchronous redaction. The documentId is the
        // correlation id used by the /api/documents endpoints.
        auditEventPublisher.auditEvent(documentId, AuditLogEvent.DOCUMENT_REDACTION_INITIATED, userId, null, null,
                "inputMimeType: " + inputMimeType.name() + ", outputMimeType: " + outputMimeType + ", policy: " + policyName);

        final String json = gson.toJson(Map.of("documentId", documentId));

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/documents/" + documentId))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));

    }

}
