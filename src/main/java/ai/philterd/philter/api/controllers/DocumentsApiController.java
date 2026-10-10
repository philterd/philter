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
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.RefusedException;
import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.api.responses.GetDocumentsResponse;
import ai.philterd.philter.api.responses.GetRedactionStatusResponse;
import ai.philterd.philter.api.responses.PendingRedactedDocuments;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.PendingDocumentEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
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
    @RequiresScope(ApiKeyScope.DOCUMENTS_READ)
    @RequestMapping(value = "/api/documents", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listDocuments(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "status", required = false) String status,
            final @RequestParam(value = "sort", required = false) String sort,
            final @RequestParam(value = "order", required = false) String order,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final String statusFilter = documentStatus(status);
        final Listings.Sort documentSort = listingSort(sort, order, DOCUMENT_SORT, "submitted", true);

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            throw new NotFoundException();
        }
        final Listings.Page<PendingDocumentEntity> page = pendingDocumentDataService.list(userId, statusFilter, documentSort,
                normalizeOffset(offset), normalizeLimit(limit));
        final List<PendingDocumentEntity> entities = page.items();

        final List<PendingRedactedDocuments> documents = new ArrayList<>();
        for (final PendingDocumentEntity entity : entities) {
            documents.add(new PendingRedactedDocuments(
                    entity.getFileName(),
                    entity.getStatus(),
                    entity.getSubmittedAt(),
                    entity.getDocumentId()
            ));
        }

        final GetDocumentsResponse response = new GetDocumentsResponse(documents, page.total());
        return new ResponseEntity<>(gson.toJson(response), HttpStatus.OK);

    }

    /** The order a listing of documents can take. */
    private static final java.util.Map<String, String> DOCUMENT_SORT = sortFields("submitted", "submitted_at", "fileName", "file_name");

    /** The statuses a listing can be narrowed to. */
    private static final List<String> DOCUMENT_STATUSES = List.of(PendingDocumentEntity.STATUS_PENDING,
            PendingDocumentEntity.STATUS_PROCESSING, PendingDocumentEntity.STATUS_COMPLETE, PendingDocumentEntity.STATUS_FAILED);

    /** The stored status a status parameter names, or {@code null} for none. Matched without regard to case. */
    private static String documentStatus(final String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        for (final String known : DOCUMENT_STATUSES) {
            if (known.equalsIgnoreCase(status.trim())) {
                return known;
            }
        }
        throw new BadRequestException("status must be one of: " + String.join(", ", DOCUMENT_STATUSES) + ".", "status");
    }

    @Operation(summary = "Get the status of an async redaction.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Unknown document id.")
    })
    @RequiresScope(ApiKeyScope.DOCUMENTS_READ)
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
            throw new NotFoundException();
        }

        final PendingDocumentEntity entity = pendingDocumentDataService.findOneByDocumentIdAndUserId(documentId, userId);
        if (entity == null) {
            throw new NotFoundException();
        }

        final GetRedactionStatusResponse response = new GetRedactionStatusResponse(entity.getStatus(), entity.getDocumentId());
        response.setEffectiveConfigurationHash(entity.getEffectiveHash());
        response.setError(entity.getErrorMessage());
        return new ResponseEntity<>(gson.toJson(response), HttpStatus.OK);

    }

    @Operation(summary = "Download the redacted document.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Unknown document id."),
            @ApiResponse(responseCode = "409", description = "Redaction not yet complete."),
            @ApiResponse(responseCode = "410", description = "Redaction failed.")
    })
    @RequiresScope(ApiKeyScope.DOCUMENTS_READ)
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
            throw new NotFoundException();
        }

        final PendingDocumentEntity entity = pendingDocumentDataService.findOneByDocumentIdAndUserId(documentId, userId);
        if (entity == null) {
            throw new NotFoundException();
        }

        if (PendingDocumentEntity.STATUS_FAILED.equals(entity.getStatus())) {
            throw new RefusedException(HttpStatus.GONE.value(),
                    "The document's redaction failed, so there is nothing to download.", ErrorReasons.DOCUMENT_FAILED);
        }

        if (!PendingDocumentEntity.STATUS_COMPLETE.equals(entity.getStatus())) {
            throw new RefusedException(HttpStatus.CONFLICT.value(),
                    "The document is still being redacted. Check its status and try again.", ErrorReasons.DOCUMENT_NOT_READY);
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
            @ApiResponse(responseCode = "200", description = "The record and any stored redacted bytes were deleted."),
            @ApiResponse(responseCode = "404", description = "Unknown document id.")
    })
    @RequiresScope(ApiKeyScope.DOCUMENTS_WRITE)
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
            throw new NotFoundException();
        }

        auditAdminCrossUserAccess(auditEventPublisher, currentRequestId(), apiKeyEntity.getUserId(), userId,
                "delete document " + documentId);

        final long deleted = pendingDocumentDataService.deleteByDocumentIdAndUserId(documentId, userId);

        if (deleted > 0) {
            auditEventPublisher.auditEvent(documentId, AuditLogEvent.REDACTED_FILE_DELETED, apiKeyEntity.getUserId(), null, null,
                    "documentId: " + documentId);
            return new ResponseEntity<>(HttpStatus.OK);
        }

        throw new NotFoundException();

    }

}
