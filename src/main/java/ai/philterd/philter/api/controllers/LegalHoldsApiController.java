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
import ai.philterd.philter.api.exceptions.BadRequestException;
import ai.philterd.philter.api.exceptions.UnauthorizedException;
import ai.philterd.philter.api.requests.LegalHoldRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetHoldsResponse;
import ai.philterd.philter.api.responses.LegalHoldConflictResponse;
import ai.philterd.philter.api.responses.LegalHoldResponse;
import ai.philterd.philter.api.responses.OwnedLegalHoldResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.LegalHoldDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.utils.PathSafeNames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
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
import java.util.Map;

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
                    + "'user' protects all governance evidence owned by the hold's owner, which is the caller or the user "
                    + "named by owner. For a user hold, scopeValue is optional; if given it must be the owner's username, "
                    + "and the owner's username is stored either way. "
                    + "Admins may place a hold on another user's evidence via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The hold was set and is now active.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LegalHoldResponse.class))),
            @ApiResponse(responseCode = "400", description = "Required fields are missing, the scope type is invalid, or the "
                    + "reference " + PathSafeNames.RULE + ", since it must be usable in a request path."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.",
                    content = @Content),
            @ApiResponse(responseCode = "409", description = "The hold was not set, and reason says why: hold_exists (a hold "
                    + "with this reference exists) or operation_in_progress (an evidence or hold operation for the owner is "
                    + "active or requires recovery).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LegalHoldConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.HOLDS_WRITE)
    @RequestMapping(value = "/api/holds", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setHold(
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
        // Checked before scopeValue, so a mistyped scope type is reported as that rather than as a missing value.
        if (!LegalHoldEntity.SCOPE_DOCUMENT_CHAIN.equals(request.getScopeType())
                && !LegalHoldEntity.SCOPE_USER.equals(request.getScopeType())) {
            throw new BadRequestException("Invalid scope type. Must be 'document_chain' or 'user'.");
        }
        final boolean userScope = LegalHoldEntity.SCOPE_USER.equals(request.getScopeType());
        if (!userScope && (request.getScopeValue() == null || request.getScopeValue().isBlank())) {
            throw new BadRequestException("scopeValue is required.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            throw new NotFoundException();
        }

        // A user hold covers everything its owner holds, so the owner is the scope. scopeValue may be left
        // out; if given it must name that owner, and either way the owner's username is what is stored.
        String scopeValue = request.getScopeValue();
        if (userScope) {
            final UserEntity ownerUser = userService.findOneById(userId);
            final String ownerUsername = ownerUser == null ? null : ownerUser.getUsername();
            if (scopeValue != null && !scopeValue.isBlank() && !scopeValue.equals(ownerUsername)) {
                throw new BadRequestException("For a user hold, scopeValue is optional; if given, it must be the "
                        + "username of the hold's owner, which is the caller or the user named by owner.");
            }
            scopeValue = ownerUsername;
        }

        final String requestId = RequestIdGenerator.generate();
        auditAdminCrossUserAccess(auditEventPublisher, requestId,
                apiKeyEntity.getUserId(), userId, "set legal hold '" + request.getReference() + "'");

        final ServiceResponse response = legalHoldDataService.create(
                requestId, request.getReference(), request.getScopeType(),
                scopeValue, request.getReason(), userId, apiKeyEntity.getUserId());

        if (response.getStatusCode() == 409) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new LegalHoldConflictResponse(response.getMessage(), response.getDetails()));
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
                    + "Admins may list another user's holds via the owner parameter, or every user's with "
                    + "all_users=true, which adds each hold's owner and requires ADMIN_CROSS_USER_ACCESS_ENABLED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of legal holds in holds, most recently set first by default, and the total. With all_users, each hold also has an owner field.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetHoldsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Both owner and all_users were given."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.")
    })
    @RequiresScope(ApiKeyScope.HOLDS_READ)
    @RequestMapping(value = "/api/holds", method = RequestMethod.GET,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<GetHoldsResponse> listHolds(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "all_users", defaultValue = "false") boolean allUsers,
            final @RequestParam(value = "q", required = false) String q,
            final @RequestParam(value = "sort", required = false) String sort,
            final @RequestParam(value = "order", required = false) String order,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final Listings.Sort holdSort = listingSort(sort, order, HOLD_SORT, "set", true);

        if (allUsers) {
            if (!mayListAllUsers(userService, apiKeyEntity.getUserId(), owner)) {
                throw new NotFoundException();
            }
            final Listings.Page<LegalHoldEntity> page =
                    legalHoldDataService.list(null, q, holdSort, normalizeOffset(offset), normalizeLimit(limit));
            final Map<ObjectId, String> owners = ownerNames(userService, page.items(), LegalHoldEntity::getUserId);
            auditAllUsersListing(auditEventPublisher, RequestIdGenerator.generate(), apiKeyEntity.getUserId(), "list legal holds");
            return ResponseEntity.ok(new GetHoldsResponse(page.items().stream().<LegalHoldResponse>map(hold -> new OwnedLegalHoldResponse(
                    hold.getReference(), hold.getScopeType(), hold.getScopeValue(), hold.getReason(), hold.getSetAt(),
                    owners.get(hold.getUserId()))).toList(), page.total()));
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            throw new NotFoundException();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId, "list legal holds");

        final Listings.Page<LegalHoldEntity> page =
                legalHoldDataService.list(userId, q, holdSort, normalizeOffset(offset), normalizeLimit(limit));

        return ResponseEntity.ok(new GetHoldsResponse(page.items().stream().map(LegalHoldsApiController::toResponse).toList(),
                page.total()));
    }

    /** The order a listing of holds can take. */
    private static final Map<String, String> HOLD_SORT = sortFields("set", "set_at", "reference", "reference");

    @Operation(summary = "Get a legal hold.",
            description = "Returns the hold with the given reference. Admins may retrieve another user's hold via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The hold details."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "No hold with the given reference exists for this user.")
    })
    @RequiresScope(ApiKeyScope.HOLDS_READ)
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
            throw new NotFoundException();
        }

        auditAdminCrossUserAccess(auditEventPublisher, RequestIdGenerator.generate(),
                apiKeyEntity.getUserId(), userId, "get legal hold '" + reference + "'");

        final LegalHoldEntity hold = legalHoldDataService.findByReference(reference, userId);
        if (hold == null) {
            throw new NotFoundException();
        }

        return ResponseEntity.ok(toResponse(hold));
    }

    @Operation(summary = "Release a legal hold.",
            description = "Removes the hold with the given reference. Once released, evidence previously covered by "
                    + "this hold may become eligible for deletion or purge if no other holds remain. "
                    + "Releasing a hold is audited. Admins may release another user's hold via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The hold was released.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The hold was not released because an evidence or hold operation "
                    + "for the owner is active or requires recovery; reason is operation_in_progress.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LegalHoldConflictResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "404", description = "No hold with the given reference exists for this user, or the owner does not exist or may "
                    + "not be reached. An unreachable owner gets the same not_found body as anything else not found, so an "
                    + "owner value cannot be used to discover accounts.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.HOLDS_WRITE)
    @RequestMapping(value = "/api/holds/{reference}", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> releaseHold(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            @PathVariable("reference") final String reference,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            throw new NotFoundException();
        }

        final String requestId = RequestIdGenerator.generate();
        auditAdminCrossUserAccess(auditEventPublisher, requestId,
                apiKeyEntity.getUserId(), userId, "release legal hold '" + reference + "'");

        final ServiceResponse response = legalHoldDataService.release(requestId, reference, userId);
        if (response.getStatusCode() == 409) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new LegalHoldConflictResponse(response.getMessage(), response.getDetails()));
        }
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage()));
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
