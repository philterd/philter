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

import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.responses.GetDocumentsResponse;
import ai.philterd.philter.api.responses.GetRedactionStatusResponse;
import ai.philterd.philter.api.responses.PendingRedactedDocuments;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Documents", description = "Operations for retrieving asynchronously redacted documents.")
@Controller
public class DocumentsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentsApiController.class);

    private final PendingDocumentDataService pendingDocumentDataService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public DocumentsApiController(final ApiKeyDataService apiKeyDataService,
                                  final ApiKeyCache apiKeyCache,
                                  final PendingDocumentDataService pendingDocumentDataService,
                                  final UserService userService,
                                  final AuditEventPublisher auditEventPublisher,
                                  final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.pendingDocumentDataService = pendingDocumentDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "List documents submitted for async redaction.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200")})
    @RequestMapping(value = "/api/documents", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listDocuments(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        final List<PendingDocumentEntity> entities = pendingDocumentDataService.findAllByUserId(userId, offset, limit);

        final List<PendingRedactedDocuments> documents = new ArrayList<>();
        for (final PendingDocumentEntity entity : entities) {
            documents.add(new PendingRedactedDocuments(
                    entity.getFileName(),
                    entity.getStatus(),
                    entity.getSubmittedAt(),
                    entity.getDocumentId()
            ));
        }

        final GetDocumentsResponse response = new GetDocumentsResponse(documents);
        return new ResponseEntity<>(gson.toJson(response), HttpStatus.OK);

    }

    @Operation(summary = "Get the status of an async redaction.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Unknown document id.")
    })
    @RequestMapping(value = "/api/documents/{documentId}/status", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getStatus(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final PendingDocumentEntity entity = pendingDocumentDataService.findOneByDocumentIdAndUserId(documentId, userId);
        if (entity == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final GetRedactionStatusResponse response = new GetRedactionStatusResponse(entity.getStatus(), entity.getDocumentId());
        return new ResponseEntity<>(gson.toJson(response), HttpStatus.OK);

    }

    @Operation(summary = "Download the redacted document.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Unknown document id."),
            @ApiResponse(responseCode = "409", description = "Redaction not yet complete."),
            @ApiResponse(responseCode = "410", description = "Redaction failed.")
    })
    @RequestMapping(value = "/api/documents/{documentId}", method = RequestMethod.GET)
    public ResponseEntity<byte[]> downloadDocument(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final PendingDocumentEntity entity = pendingDocumentDataService.findOneByDocumentIdAndUserId(documentId, userId);
        if (entity == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        if (PendingDocumentEntity.STATUS_FAILED.equals(entity.getStatus())) {
            return new ResponseEntity<>(HttpStatus.GONE);
        }

        if (!PendingDocumentEntity.STATUS_COMPLETE.equals(entity.getStatus())) {
            return new ResponseEntity<>(HttpStatus.CONFLICT);
        }

        final MediaType mediaType = entity.getOutputMimeType() != null
                ? MediaType.parseMediaType(entity.getOutputMimeType())
                : MediaType.APPLICATION_OCTET_STREAM;

        // Audit access to the redacted output.
        auditEventPublisher.auditEvent(documentId, AuditLogEvent.REDACTED_FILE_DOWNLOAD, apiKeyEntity.getUserId(), null, null,
                "documentId: " + documentId);

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(mediaType)
                .body(entity.getOutput());

    }

    @Operation(summary = "Delete an async redaction record.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Unknown document id.")
    })
    @RequestMapping(value = "/api/documents/{documentId}", method = RequestMethod.DELETE)
    public ResponseEntity<Void> deleteDocument(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("documentId") String documentId,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(), apiKeyEntity.getUserId(), userId,
                "delete document " + documentId);

        final long deleted = pendingDocumentDataService.deleteByDocumentIdAndUserId(documentId, userId);

        if (deleted > 0) {
            auditEventPublisher.auditEvent(documentId, AuditLogEvent.REDACTED_FILE_DELETED, apiKeyEntity.getUserId(), null, null,
                    "documentId: " + documentId);
            return new ResponseEntity<>(HttpStatus.OK);
        }

        return new ResponseEntity<>(HttpStatus.NOT_FOUND);

    }

}
