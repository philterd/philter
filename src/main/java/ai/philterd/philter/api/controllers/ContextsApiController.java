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
import ai.philterd.philter.api.responses.ContextEntriesExport;
import ai.philterd.philter.api.responses.ContextEntryExport;
import ai.philterd.philter.api.responses.ContextEntryView;
import ai.philterd.philter.api.responses.ContextConflictResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetAllUsersContextsResponse;
import ai.philterd.philter.api.responses.GetContextEntriesResponse;
import ai.philterd.philter.api.responses.ImportContextEntriesResponse;
import ai.philterd.philter.api.responses.GetContextResponse;
import ai.philterd.philter.api.responses.GetContextsResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.services.RequestIdGenerator;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.utils.PathSafeNames;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

@Tag(name = "Contexts", description = "Operations for creating and managing contexts.")
@Controller
public class ContextsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextsApiController.class);

    /** Matches a lowercase/uppercase hex-encoded SHA-256 digest (the token hash format). */
    /** The width a token hash is written in; the hash itself is keyed, see ContextTokenHasher. */
    private static final Pattern TOKEN_HASH_HEX = Pattern.compile("^[a-fA-F0-9]{64}$");

    private final ContextDataService contextService;
    private final ContextEntryDataService contextEntryService;
    private final PendingDocumentDataService pendingDocumentDataService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public ContextsApiController(final ContextDataService contextService,
                                 final ContextEntryDataService contextEntryService,
                                 final PendingDocumentDataService pendingDocumentDataService,
                                 final UserService userService,
                                 final ApiKeyDataService apiKeyDataService,
                                 final AuditEventPublisher auditEventPublisher,
                                 final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.contextService = contextService;
        this.contextEntryService = contextEntryService;
        this.pendingDocumentDataService = pendingDocumentDataService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    /**
     * Whether the user behind the given API key is an admin. A context may be deleted only by its
     * creator or by an admin.
     */
    private boolean isAdmin(final ObjectId userId) {
        final UserEntity user = userService.findOneById(userId);
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }

    /**
     * Resolves the context that the caller is allowed to export from or import into. Context names are
     * unique per user, so a bare name identifies the caller's own context. To reach a context owned by
     * a different user, an admin supplies that user's username via {@code ownerEmail}; this is an
     * admin-only capability.
     *
     * <p>Returns {@code null} when the caller is not authorized (a non-admin naming another user as
     * owner), when the named owner does not exist, or when no such context exists. The callers map all
     * of these to a 404 so the endpoints never reveal the existence of a context — or a user — the
     * caller is not authorized to access.
     *
     * @param name       The context name.
     * @param callerUserId The id of the user making the request.
     * @param ownerEmail The email of the context's owner, or null/blank for the caller's own context.
     */
    private ContextEntity resolveAuthorizedContext(final String name, final ObjectId callerUserId, final String ownerEmail) {

        final ObjectId targetUserId;

        if (ownerEmail == null || ownerEmail.isBlank()) {
            // No owner specified: operate on the caller's own context.
            targetUserId = callerUserId;
        } else {
            // Deactivated owners included, as in resolveTargetUserId.
            final UserEntity owner = userService.findAnyByUsername(ownerEmail);
            if (owner == null) {
                return null;
            }
            // Reaching another user's context requires admin rights AND cross-user access being enabled
            // (the ADMIN_CROSS_USER_ACCESS_ENABLED kill switch). Naming your own email always works.
            if (!owner.getId().equals(callerUserId)
                    && !(isCrossUserAccessEnabled() && isAdmin(callerUserId))) {
                return null;
            }
            targetUserId = owner.getId();
        }

        return contextService.findOne(name, targetUserId);

    }

    @Operation(summary = "Get the names of existing contexts.",
            description = "Get the names of the caller's contexts, paged. Admins may list another user's contexts "
                    + "with owner, or every user's with all_users=true, which returns each context's name and "
                    + "owner and requires ADMIN_CROSS_USER_ACCESS_ENABLED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context names; with all_users, each context's name and owner. The schema is set in ApiDocumentationConfig."),
            @ApiResponse(responseCode = "400", description = "Both owner and all_users were given."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it; or all_users was given by a caller who is not an administrator, or with cross-user access disabled.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_READ)
    @RequestMapping(value = "/api/contexts", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getContexts(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "all_users", defaultValue = "false") boolean allUsers,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId callerUserId = apiKeyEntity.getUserId();

        if (allUsers) {
            if (!mayListAllUsers(userService, callerUserId, owner)) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            final List<ContextEntity> contextEntities =
                    contextService.findAllAcrossUsers(normalizeOffset(offset), normalizeLimit(limit));
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXTS_RETRIEVED, callerUserId, getClientIpAddress(httpServletRequest));
            auditAllUsersListing(auditEventPublisher, requestId, callerUserId, "list contexts");
            return new ResponseEntity<>(gson.toJson(new GetAllUsersContextsResponse(ownedNames(userService, contextEntities,
                    ContextEntity::getContextName, ContextEntity::getUserId))), HttpStatus.OK);
        }

        // The caller's own contexts, or — for an admin supplying owner — another user's. A null result
        // (non-admin naming another user, or unknown user) maps to 404 so it never reveals the user's existence.
        final ObjectId userId = resolveTargetUserId(userService, callerUserId, owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<ContextEntity> contextEntities = contextService.findAll(userId, normalizeOffset(offset), normalizeLimit(limit));

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXTS_RETRIEVED, callerUserId, getClientIpAddress(httpServletRequest));
        auditAdminCrossUserAccess(auditEventPublisher, requestId, callerUserId, userId, "list contexts");

        final List<String> contexts = new ArrayList<>();

        for(final ContextEntity contextEntity : contextEntities) {
            contexts.add(contextEntity.getContextName());
        }

        final GetContextsResponse getContextsResponse = new GetContextsResponse(contexts);

        return new ResponseEntity<>(gson.toJson(getContextsResponse), HttpStatus.OK);

    }

    @Operation(summary = "Get the details of a context.",
            description = "Get the details of a context with the provided name: its size and its entries counted "
                    + "by filter type. The counts are computed in the database and sum to the size, with entries "
                    + "that have no filter type counted as untyped.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context's size and per-filter-type counts.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetContextResponse.class))),
            @ApiResponse(responseCode = "404", description = "A context with the given name does not exist."),
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_READ)
    @RequestMapping(value = "/api/contexts/{name}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "get context '" + name + "'");

        final ContextEntity contextEntity = contextService.findOne(name, userId);

        if(contextEntity == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        // One aggregation, so the per-type counts always sum to the size, even while entries are written.
        final Map<String, Long> filterTypes = new TreeMap<>();
        long size = 0;
        long untyped = 0;
        for (final Map.Entry<String, Long> count : contextEntryService.getFilterTypeCounts(name, userId).entrySet()) {
            size += count.getValue();
            if (count.getKey() == null) {
                untyped += count.getValue();
            } else {
                filterTypes.put(count.getKey(), count.getValue());
            }
        }

        final GetContextResponse getContextResponse = new GetContextResponse(size, filterTypes, untyped,
                contextEntity.isDisambiguation(), contextEntity.isLedger());

        return new ResponseEntity<>(gson.toJson(getContextResponse), HttpStatus.OK);

    }

    @Operation(summary = "Create a context.", description = "Creates a context. A user can have at most "
            + ContextDataService.MAXIMUM_CONTEXTS_PER_USER + " contexts, counting default.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was created."),
            @ApiResponse(responseCode = "400", description = "The name is missing or blank, or "
                    + PathSafeNames.RULE + ", since it must be usable in a request path."),
            @ApiResponse(responseCode = "409", description = "The context was not created, and reason says why: "
                    + "context_exists (the caller already has a context with this name) or context_limit_reached "
                    + "(the caller already has the most contexts a user may have).",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ContextConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam("name") String name,
            final @RequestParam(value = "entity_type_disambiguation", required = false, defaultValue = "false") boolean disambiguation,
            final @RequestParam(value = "ledger", required = false, defaultValue = "false") boolean ledger,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "create context '" + name + "'");

        final ServiceResponse serviceResponse = contextService.create(name, userId, disambiguation, ledger);

        if(serviceResponse.isSuccessful()) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_CREATED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));
            return new ResponseEntity<>(new GenericResponse("Context created."), HttpStatus.OK);

        } else {

            // A conflict with the caller's existing contexts, a duplicate name or the limit, is 409 with a
            // reason; the other failures the service reports are a blank name and one that cannot be
            // used in a request path.
            if (serviceResponse.getStatusCode() == 409) {
                return new ResponseEntity<>(new ContextConflictResponse(serviceResponse.getMessage(),
                        serviceResponse.getDetails()), HttpStatus.CONFLICT);
            }
            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.BAD_REQUEST);

        }

    }

    @Operation(summary = "Delete a context by name in the query.",
            description = "Deletes a context named in the name query parameter, for a context whose name cannot be "
                    + "used in a request path. Otherwise the same as DELETE /api/contexts/{name}.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was deleted."),
            @ApiResponse(responseCode = "404", description = "There is no context with that name, or the owner does not exist "
                    + "or the caller may not reach it. The body carries a message."),
            @ApiResponse(responseCode = "409", description = "The context has open asynchronous redaction jobs and cannot be deleted.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> deleteContextNamedInQuery(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam("name") String name,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {
        return deleteContext(authorizationHeader, name, owner, requestId, httpServletRequest);
    }

    @Operation(summary = "Delete a context.", description = "Delete an existing context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was deleted."),
            @ApiResponse(responseCode = "404", description = "There is no context with that name, or the owner does not exist "
                    + "or the caller may not reach it. The body carries a message."),
            @ApiResponse(responseCode = "409", description = "The context has open asynchronous redaction jobs and cannot be deleted.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts/{name}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> deleteContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "delete context '" + name + "'");

        if (pendingDocumentDataService.hasOpenJobsForContext(userId, name)) {
            return new ResponseEntity<>(
                    new GenericResponse("Context has pending or processing redaction jobs; cannot delete."),
                    HttpStatus.CONFLICT);
        }

        // The admin flag describes the *caller*, not the context's owner: it is the service's own
        // authorization gate, and passing the target's role would make it always true.
        final ServiceResponse serviceResponse =
                contextService.deleteByName(name, userId, isAdmin(apiKeyEntity.getUserId()));

        if(serviceResponse.isSuccessful()) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_DELETED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));
            return new ResponseEntity<>(new GenericResponse("Context deleted."), HttpStatus.OK);

        } else if(serviceResponse.getStatusCode() == 403) {

            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.FORBIDDEN);

        } else if(serviceResponse.getStatusCode() == 404) {

            // A missing context is 404, as a missing policy, list, or hold is.
            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.NOT_FOUND);

        } else {

            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.BAD_REQUEST);

        }

    }

    @Operation(summary = "Update a context's settings.", description = "Changes a context's entity_type_disambiguation "
            + "and ledger settings. Only the settings given change; one left out keeps its current value. Recorded as a "
            + "context_updated audit event naming each setting that changed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was updated."),
            @ApiResponse(responseCode = "400", description = "Neither setting was given."),
            @ApiResponse(responseCode = "404", description = "Context not found.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts/{name}", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> updateContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @Parameter(description = "Whether to apply entity-type span disambiguation. Left out, it keeps its current value.")
            @RequestParam(value = "entity_type_disambiguation", required = false) Boolean disambiguation,
            final @Parameter(description = "Whether to record redactions in the ledger. Left out, it keeps its current value.")
            @RequestParam(value = "ledger", required = false) Boolean ledger,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        // Refused rather than done as nothing, so a caller who misspelled a parameter learns of it.
        if (disambiguation == null && ledger == null) {
            throw new BadRequestException("Give entity_type_disambiguation, ledger, or both.");
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "update context '" + name + "'");

        final ServiceResponse response = contextService.updateSettings(requestId, name, userId, disambiguation, ledger,
                apiKeyEntity.getUserId(), apiKeyEntity.getId());

        return new ResponseEntity<>(new GenericResponse(response.getMessage()),
                response.isSuccessful() ? HttpStatus.OK : HttpStatus.NOT_FOUND);

    }

    @Operation(summary = "List entries within a context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Context not found.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_READ)
    @RequestMapping(value = "/api/contexts/{name}/entries", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> listEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit,
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
                "list entries in context '" + name + "'");

        if (contextService.findOne(name, userId) == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<ContextEntryEntity> entries = contextEntryService.findAllByUserIdAndContext(userId, name, normalizeOffset(offset), normalizeLimit(limit));
        final int total = contextEntryService.countByUserIdAndContext(userId, name);

        final List<ContextEntryView> views = new ArrayList<>(entries.size());
        for (final ContextEntryEntity entry : entries) {
            views.add(new ContextEntryView(
                    entry.getId() != null ? entry.getId().toHexString() : null,
                    entry.getReplacement(),
                    entry.getFilterType(),
                    entry.getReads(),
                    entry.getTimestamp()));
        }

        return new ResponseEntity<>(gson.toJson(new GetContextEntriesResponse(views, total)), HttpStatus.OK);

    }

    @Operation(summary = "Empty all entries from a context.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts/{name}/entries", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> emptyEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "empty entries in context '" + name + "'");

        final ServiceResponse response = contextService.emptyByName(name, userId);

        if (response.isSuccessful()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_PURGED, apiKeyEntity.getUserId(), null,
                    getClientIpAddress(httpServletRequest), "context: " + name);
        }

        return new ResponseEntity<>(new GenericResponse(response.getMessage()),
                response.isSuccessful() ? HttpStatus.OK : HttpStatus.NOT_FOUND);

    }

    @Operation(summary = "Delete a single context entry by id.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts/{name}/entries/{entryId}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> deleteEntry(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @PathVariable("entryId") String entryId,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        if (!ObjectId.isValid(entryId)) {
            return new ResponseEntity<>(new GenericResponse("Invalid entry id."), HttpStatus.BAD_REQUEST);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                "delete entry " + entryId + " in context '" + name + "'");

        final long deleted = contextEntryService.deleteByIdAndUserIdAndContext(new ObjectId(entryId), userId, name);

        if (deleted > 0) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRY_DELETED, apiKeyEntity.getUserId(), null,
                    getClientIpAddress(httpServletRequest), "context: " + name + ", entryId: " + entryId);
            return new ResponseEntity<>(new GenericResponse("Entry deleted."), HttpStatus.OK);
        }

        return new ResponseEntity<>(new GenericResponse("Entry not found."), HttpStatus.NOT_FOUND);

    }

    @Operation(summary = "Export a context's mapping table.",
            description = "Returns the complete token-to-replacement mapping table for a context in a portable JSON form "
                    + "that can be re-imported into another context or environment. Only token hashes (never the original "
                    + "tokens) and their replacements are returned.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Context not found.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_READ)
    @RequestMapping(value = "/api/contexts/{name}/entries/export", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        // The caller's own context by name, or — for an admin supplying owner — another user's context.
        final ContextEntity context = resolveAuthorizedContext(name, userId, owner);
        if (context == null) {
            // Audit the denied/not-found attempt. The two cases are deliberately indistinguishable so
            // the response does not reveal whether the context exists.
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_EXPORT_DENIED, userId, null,
                    getClientIpAddress(httpServletRequest), "context: " + name);
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final ObjectId ownerUserId = context.getUserId();

        final List<ContextEntryEntity> entries = contextEntryService.findAllByUserIdAndContext(ownerUserId, name);

        final List<ContextEntryExport> exported = new ArrayList<>(entries.size());
        for (final ContextEntryEntity entry : entries) {
            exported.add(new ContextEntryExport(
                    entry.getTokenHash(),
                    entry.getReplacement(),
                    entry.getFilterType(),
                    entry.isReplacementUuid()));
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_EXPORTED, userId, null,
                getClientIpAddress(httpServletRequest), "context: " + name + ", owner: " + ownerUserId + ", count: " + exported.size());

        final HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "-context-export.json\"");

        return new ResponseEntity<>(gson.toJson(new ContextEntriesExport(name, exported)), headers, HttpStatus.OK);

    }

    @Operation(summary = "Import a mapping table into a context.",
            description = "Imports token-to-replacement mappings (as produced by the export endpoint) into a context. "
                    + "By default an incoming token that already exists in the context is skipped; pass on_conflict=overwrite "
                    + "to replace existing replacements instead.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "400", description = "The payload or on_conflict value is invalid."),
            @ApiResponse(responseCode = "404", description = "Context not found.")
    })
    @RequiresScope(ApiKeyScope.CONTEXTS_WRITE)
    @RequestMapping(value = "/api/contexts/{name}/entries/import", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> importEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "on_conflict", required = false, defaultValue = "skip") String onConflict,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody String body,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        // The caller's own context by name, or — for an admin supplying owner — another user's context.
        // This authorization check runs before any input validation (on_conflict and the payload) so an
        // unauthorized caller is denied (and audited) regardless of whether the rest of the request is
        // well-formed.
        final ContextEntity context = resolveAuthorizedContext(name, userId, owner);
        if (context == null) {
            // Audit the denied/not-found attempt. The two cases are deliberately indistinguishable so
            // the response does not reveal whether the context exists.
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_IMPORT_DENIED, userId, null,
                    getClientIpAddress(httpServletRequest), "context: " + name);
            return new ResponseEntity<>(new GenericResponse("Context not found."), HttpStatus.NOT_FOUND);
        }

        final boolean overwrite;
        if ("skip".equalsIgnoreCase(onConflict)) {
            overwrite = false;
        } else if ("overwrite".equalsIgnoreCase(onConflict)) {
            overwrite = true;
        } else {
            return new ResponseEntity<>(new GenericResponse("on_conflict must be 'skip' or 'overwrite'."), HttpStatus.BAD_REQUEST);
        }

        final ContextEntriesExport payload;
        try {
            payload = gson.fromJson(body, ContextEntriesExport.class);
        } catch (final Exception ex) {
            return new ResponseEntity<>(new GenericResponse("Malformed import payload."), HttpStatus.BAD_REQUEST);
        }

        if (payload == null || payload.getEntries() == null) {
            return new ResponseEntity<>(new GenericResponse("Import payload must contain an 'entries' array."), HttpStatus.BAD_REQUEST);
        }

        // Validate the entire payload before writing anything, so a malformed entry cannot leave a
        // partially-imported mapping table.
        for (final ContextEntryExport entry : payload.getEntries()) {
            if (entry == null || entry.getTokenHash() == null || !TOKEN_HASH_HEX.matcher(entry.getTokenHash()).matches()) {
                return new ResponseEntity<>(new GenericResponse("Each entry requires a valid tokenHash: 64 hexadecimal characters, as produced by an export from this deployment."), HttpStatus.BAD_REQUEST);
            }
            if (entry.getReplacement() == null || entry.getReplacement().isEmpty()) {
                return new ResponseEntity<>(new GenericResponse("Each entry requires a non-empty replacement."), HttpStatus.BAD_REQUEST);
            }
        }

        final ObjectId ownerUserId = context.getUserId();

        int inserted = 0, overwritten = 0, skipped = 0;
        for (final ContextEntryExport entry : payload.getEntries()) {
            final ContextEntryDataService.ImportOutcome outcome = contextEntryService.importEntryByHash(
                    ownerUserId, name, entry.getTokenHash(), entry.getReplacement(), entry.getFilterType(),
                    entry.isReplacementUuid(), overwrite);
            switch (outcome) {
                case INSERTED -> inserted++;
                case OVERWRITTEN -> overwritten++;
                case SKIPPED -> skipped++;
            }
        }

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_IMPORTED, userId, null,
                getClientIpAddress(httpServletRequest),
                "context: " + name + ", owner: " + ownerUserId + ", inserted: " + inserted + ", overwritten: " + overwritten + ", skipped: " + skipped);

        return new ResponseEntity<>(
                new ImportContextEntriesResponse(payload.getEntries().size(), inserted, overwritten, skipped),
                HttpStatus.OK);

    }

}
