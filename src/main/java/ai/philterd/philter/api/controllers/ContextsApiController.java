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
import ai.philterd.philter.api.responses.ContextEntriesExport;
import ai.philterd.philter.api.responses.ContextEntryExport;
import ai.philterd.philter.api.responses.ContextEntryView;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetContextEntriesResponse;
import ai.philterd.philter.api.responses.ImportContextEntriesResponse;
import ai.philterd.philter.api.responses.GetContextResponse;
import ai.philterd.philter.api.responses.GetContextsResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.entities.ContextEntryEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.PendingDocumentDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
import java.util.regex.Pattern;

@Tag(name = "Contexts", description = "Operations for creating and managing contexts.")
@Controller
public class ContextsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextsApiController.class);

    /** Matches a lowercase/uppercase hex-encoded SHA-256 digest (the token hash format). */
    private static final Pattern SHA256_HEX = Pattern.compile("^[a-fA-F0-9]{64}$");

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
        final ai.philterd.philter.data.entities.UserEntity user = userService.findOneById(userId);
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }

    /**
     * Resolves the context that the caller is allowed to export from or import into. Access is
     * limited to the context's creator or an admin: the creator is matched by an owner-scoped lookup,
     * and an admin may additionally reach a context created by another user. Returns {@code null}
     * when the caller is neither the creator nor an admin, or when no such context exists — both
     * cases are mapped to a 404 by the callers so the endpoints never reveal the existence of a
     * context the caller is not authorized to access.
     */
    private ContextEntity resolveAuthorizedContext(final String name, final ObjectId userId) {

        final ContextEntity owned = contextService.findOne(name, userId);
        if (owned != null) {
            // The caller created this context.
            return owned;
        }

        if (isAdmin(userId)) {
            // An admin may export/import a context created by another user.
            return contextService.findOneByName(name);
        }

        return null;

    }

    @Operation(summary = "Get the names of existing contexts.", description = "Get the names of existing contexts.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200")
    })
    @RequestMapping(value = "/api/contexts", method = RequestMethod.GET)
    public ResponseEntity<String> getContexts(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final List<ContextEntity> contextEntities = contextService.findAll(userId);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXTS_RETRIEVED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));

        final List<String> contexts = new ArrayList<>();

        for(final ContextEntity contextEntity : contextEntities) {
            contexts.add(contextEntity.getContextName());
        }

        final GetContextsResponse getContextsResponse = new GetContextsResponse(contexts);

        return new ResponseEntity<>(gson.toJson(getContextsResponse), HttpStatus.OK);

    }

    @Operation(summary = "Get the details of a context.", description = "Get the details of a context with the provided name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "A context with the given name does not exist."),
    })
    @RequestMapping(value = "/api/contexts/{name}", method = RequestMethod.GET)
    public ResponseEntity<String> getContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final ContextEntity contextEntity = contextService.findOne(name, userId);

        if(contextEntity == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<?> entries = contextEntryService.findAllByUserIdAndContext(userId, name, Integer.MAX_VALUE);
        final GetContextResponse getContextResponse = new GetContextResponse(entries.size());

        return new ResponseEntity<>(gson.toJson(getContextResponse), HttpStatus.OK);

    }

    @Operation(summary = "Create a context.", description = "Create a new context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was created."),
            @ApiResponse(responseCode = "400", description = "The context could not be created."),
            @ApiResponse(responseCode = "409", description = "A context with this name already exists.")
    })
    @RequestMapping(value = "/api/contexts", method = RequestMethod.POST)
    public ResponseEntity<GenericResponse> createContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam("name") String name,
            final @RequestParam(value = "entity_type_disambiguation", required = false, defaultValue = "false") boolean disambiguation,
            final @RequestParam(value = "ledger", required = false, defaultValue = "false") boolean ledger,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        final ServiceResponse serviceResponse = contextService.create(name, userId, disambiguation, ledger);

        if(serviceResponse.isSuccessful()) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_CREATED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));
            return new ResponseEntity<>(new GenericResponse("Context created."), HttpStatus.OK);

        } else {

            // A duplicate (globally non-unique) name is a conflict; other validation failures are 400.
            final HttpStatus status = serviceResponse.getStatusCode() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), status);

        }

    }

    @Operation(summary = "Delete a context.", description = "Delete an existing context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was deleted."),
            @ApiResponse(responseCode = "400", description = "The context could not be deleted."),
            @ApiResponse(responseCode = "409", description = "The context has open asynchronous redaction jobs and cannot be deleted.")
    })
    @RequestMapping(value = "/api/contexts/{name}", method = RequestMethod.DELETE)
    public ResponseEntity<GenericResponse> deleteContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        if (pendingDocumentDataService.hasOpenJobsForContext(userId, name)) {
            return new ResponseEntity<>(
                    new GenericResponse("Context has pending or processing redaction jobs; cannot delete."),
                    HttpStatus.CONFLICT);
        }

        final ServiceResponse serviceResponse = contextService.deleteByName(name, userId, isAdmin(userId));

        if(serviceResponse.isSuccessful()) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_DELETED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));
            return new ResponseEntity<>(new GenericResponse("Context deleted."), HttpStatus.OK);

        } else if(serviceResponse.getStatusCode() == 403) {

            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.FORBIDDEN);

        } else {

            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.BAD_REQUEST);

        }

    }

    @Operation(summary = "Update a context's settings.", description = "Update the entity_type_disambiguation and ledger flags on an existing context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Context not found.")
    })
    @RequestMapping(value = "/api/contexts/{name}", method = RequestMethod.PUT)
    public ResponseEntity<GenericResponse> updateContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "entity_type_disambiguation", required = false, defaultValue = "false") boolean disambiguation,
            final @RequestParam(value = "ledger", required = false, defaultValue = "false") boolean ledger) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ServiceResponse response = contextService.updateSettings(name, apiKeyEntity.getUserId(), disambiguation, ledger);

        return new ResponseEntity<>(new GenericResponse(response.getMessage()),
                response.isSuccessful() ? HttpStatus.OK : HttpStatus.NOT_FOUND);

    }

    @Operation(summary = "List entries within a context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "Context not found.")
    })
    @RequestMapping(value = "/api/contexts/{name}/entries", method = RequestMethod.GET)
    public ResponseEntity<String> listEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        if (contextService.findOne(name, userId) == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<ContextEntryEntity> entries = contextEntryService.findAllByUserIdAndContext(userId, name, offset, limit);
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
    @RequestMapping(value = "/api/contexts/{name}/entries", method = RequestMethod.DELETE)
    public ResponseEntity<GenericResponse> emptyEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ServiceResponse response = contextService.emptyByName(name, apiKeyEntity.getUserId());

        if (response.isSuccessful()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_PURGED, apiKeyEntity.getUserId(), null,
                    getClientIpAddress(httpServletRequest), "context: " + name);
        }

        return new ResponseEntity<>(new GenericResponse(response.getMessage()),
                response.isSuccessful() ? HttpStatus.OK : HttpStatus.NOT_FOUND);

    }

    @Operation(summary = "Delete a single context entry by id.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "404")})
    @RequestMapping(value = "/api/contexts/{name}/entries/{entryId}", method = RequestMethod.DELETE)
    public ResponseEntity<GenericResponse> deleteEntry(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @PathVariable("entryId") String entryId,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        if (!ObjectId.isValid(entryId)) {
            return new ResponseEntity<>(new GenericResponse("Invalid entry id."), HttpStatus.BAD_REQUEST);
        }

        final long deleted = contextEntryService.deleteByIdAndUserId(new ObjectId(entryId), apiKeyEntity.getUserId());

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
    @RequestMapping(value = "/api/contexts/{name}/entries/export", method = RequestMethod.GET)
    public ResponseEntity<String> exportEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        // Only the context's creator or an admin may export it.
        final ContextEntity context = resolveAuthorizedContext(name, userId);
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
    @RequestMapping(value = "/api/contexts/{name}/entries/import", method = RequestMethod.POST)
    public ResponseEntity<?> importEntries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestParam(value = "on_conflict", required = false, defaultValue = "skip") String onConflict,
            final @RequestBody String body,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
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
            if (entry == null || entry.getTokenHash() == null || !SHA256_HEX.matcher(entry.getTokenHash()).matches()) {
                return new ResponseEntity<>(new GenericResponse("Each entry requires a valid SHA-256 token_hash."), HttpStatus.BAD_REQUEST);
            }
            if (entry.getReplacement() == null || entry.getReplacement().isEmpty()) {
                return new ResponseEntity<>(new GenericResponse("Each entry requires a non-empty replacement."), HttpStatus.BAD_REQUEST);
            }
        }

        final ObjectId userId = apiKeyEntity.getUserId();

        // Only the context's creator or an admin may import into it.
        final ContextEntity context = resolveAuthorizedContext(name, userId);
        if (context == null) {
            // Audit the denied/not-found attempt. The two cases are deliberately indistinguishable so
            // the response does not reveal whether the context exists.
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_ENTRIES_IMPORT_DENIED, userId, null,
                    getClientIpAddress(httpServletRequest), "context: " + name);
            return new ResponseEntity<>(new GenericResponse("Context not found."), HttpStatus.NOT_FOUND);
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
