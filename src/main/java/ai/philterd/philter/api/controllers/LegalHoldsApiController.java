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

import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.requests.LegalHoldRequest;
import ai.philterd.philter.api.responses.LegalHoldResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.bson.types.ObjectId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

@Tag(name = "Legal Holds",
        description = "Operations for setting, listing, retrieving, and releasing legal holds. "
                + "An active hold blocks all deletion and purge of the evidence it covers until the hold is released. "
                + "Every hold lifecycle event is audited.")
@Controller
public class LegalHoldsApiController extends AbstractApiController {

    private final LegalHoldDataService legalHoldDataService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;

    public LegalHoldsApiController(final LegalHoldDataService legalHoldDataService,
                                    final UserService userService,
                                    final ApiKeyDataService apiKeyDataService,
                                    final AuditEventPublisher auditEventPublisher,
                                    final ApiKeyCache apiKeyCache) {
        super(apiKeyDataService, apiKeyCache);
        this.legalHoldDataService = legalHoldDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Operation(summary = "Set a legal hold.",
            description = "Creates a named hold that blocks deletion and purge of the specified evidence until the hold "
                    + "is released. The reference must be unique for the calling user. "
                    + "Two scope types are supported: 'document_chain' protects a specific document's ledger chain; "
                    + "'user' protects all governance evidence owned by the user. "
                    + "Admins may place a hold on another user's evidence via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The hold was set and is now active."),
            @ApiResponse(responseCode = "400", description = "Required fields are missing or the scope type is invalid."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller is not an admin."),
            @ApiResponse(responseCode = "409", description = "A hold with the given reference already exists for this user.")
    })
    @RequestMapping(value = "/api/holds", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<LegalHoldResponse> setHold(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody LegalHoldRequest request) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        if (request.getReference() == null || request.getReference().isBlank()) {
            throw new BadRequestException("reference is required.");
        }
        if (request.getScopeType() == null || request.getScopeType().isBlank()) {
            throw new BadRequestException("scopeType is required.");
        }
        if (request.getScopeValue() == null || request.getScopeValue().isBlank()) {
            throw new BadRequestException("scopeValue is required.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String requestId = RequestIdGenerator.generate();
        auditAdminCrossUserAccess(auditEventPublisher, requestId,
                apiKeyEntity.getUserId(), userId, "set legal hold '" + request.getReference() + "'");

        final ServiceResponse response = legalHoldDataService.create(
                requestId, request.getReference(), request.getScopeType(),
                request.getScopeValue(), request.getReason(), userId, apiKeyEntity.getUserId());

        if (response.getStatusCode() == 409) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        if (!response.isSuccessful()) {
            throw new BadRequestException(response.getMessage());
        }

        final LegalHoldEntity created = legalHoldDataService.findByReference(
                request.getReference(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @Operation(summary = "List legal holds.",
            description = "Returns the caller's active legal holds, paged and ordered by set date descending. "
                    + "Admins may list another user's holds via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Array of legal holds, most recently set first."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller is not an admin.")
    })
    @RequestMapping(value = "/api/holds", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<List<LegalHoldResponse>> listHolds(
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
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId, "list legal holds");

        final List<LegalHoldEntity> holds = legalHoldDataService.findAllByUserId(
                userId, offset, Math.min(limit, 100));

        return ResponseEntity.ok(holds.stream().map(LegalHoldsApiController::toResponse).toList());
    }

    @Operation(summary = "Get a legal hold.",
            description = "Returns the hold with the given reference. Admins may retrieve another user's hold via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The hold details."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "No hold with the given reference exists for this user.")
    })
    @RequestMapping(value = "/api/holds/{reference}", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<LegalHoldResponse> getHold(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("reference") final String reference,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId, "get legal hold '" + reference + "'");

        final LegalHoldEntity hold = legalHoldDataService.findByReference(reference, userId);
        if (hold == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(toResponse(hold));
    }

    @Operation(summary = "Release a legal hold.",
            description = "Removes the hold with the given reference. Once released, evidence previously covered by "
                    + "this hold may become eligible for deletion or purge if no other holds remain. "
                    + "Releasing a hold is audited. Admins may release another user's hold via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The hold was released."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "No hold with the given reference exists for this user.")
    })
    @RequestMapping(value = "/api/holds/{reference}", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Void> releaseHold(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("reference") final String reference,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String requestId = RequestIdGenerator.generate();
        auditAdminCrossUserAccess(auditEventPublisher, requestId,
                apiKeyEntity.getUserId(), userId, "release legal hold '" + reference + "'");

        final ServiceResponse response = legalHoldDataService.release(requestId, reference, userId);
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok().build();
    }

    private static LegalHoldResponse toResponse(final LegalHoldEntity entity) {
        return new LegalHoldResponse(
                entity.getReference(),
                entity.getScopeType(),
                entity.getScopeValue(),
                entity.getReason(),
                entity.getSetAt());
    }
}
