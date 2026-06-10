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
import ai.philterd.philter.api.requests.RedactListsRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.RedactListsResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.RedactListsEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static ai.philterd.philter.data.services.RedactListsDataService.MAXIMUM_TERMS_PER_LIST;
import static ai.philterd.philter.data.services.RedactListsDataService.MAXIMUM_TERM_LENGTH;

/**
 * Manages an account's always-redact and never-redact lists — the terms that are unconditionally
 * redacted or unconditionally preserved across all of the user's redaction policies and contexts.
 * The lists are a per-user singleton resource: there is always exactly one (possibly empty) pair of
 * lists per account, so there is no create/delete, only get and replace.
 */
@Tag(name = "Always/Never Redact Lists", description = "Operations for managing an account's always-redact and never-redact term lists.")
@Controller
public class RedactListsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedactListsApiController.class);

    private final RedactListsDataService redactListsService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public RedactListsApiController(final RedactListsDataService redactListsService,
                                    final UserService userService,
                                    final ApiKeyDataService apiKeyDataService,
                                    final AuditEventPublisher auditEventPublisher,
                                    final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.redactListsService = redactListsService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "Get the always-redact and never-redact lists.",
            description = "Returns the account's always-redact and never-redact lists. Both lists are always "
                    + "present; an account with no saved terms returns empty arrays. Admins may read another user's "
                    + "lists by passing that user's email as owner.")
    @ApiResponses(value = {@ApiResponse(responseCode = "200"), @ApiResponse(responseCode = "401"), @ApiResponse(responseCode = "404")})
    @RequestMapping(value = "/api/redact-lists", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getRedactLists(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final RedactListsEntity entity = redactListsService.find(userId);

        // The lists are a singleton config resource: a missing document means "no terms yet", which is
        // an empty pair of lists rather than a 404.
        final List<String> alwaysRedact = entity != null ? entity.getTermsToAlwaysRedact() : Collections.emptyList();
        final List<String> neverRedact = entity != null ? entity.getTermsToNeverRedact() : Collections.emptyList();

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.REDACT_LISTS_RETRIEVED, apiKeyEntity.getUserId(), null,
                getClientIpAddress(httpServletRequest), "owner: " + userId);
        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId, "get redact lists");

        return new ResponseEntity<>(gson.toJson(new RedactListsResponse(alwaysRedact, neverRedact)), HttpStatus.OK);

    }

    @Operation(summary = "Replace the always-redact and never-redact lists.",
            description = "Replaces both lists in full with the supplied contents - this is not a merge. Each field "
                    + "is the complete desired contents of that list; a list that is omitted or sent as an empty array "
                    + "is cleared. Terms are trimmed and blank entries are dropped. Each list may contain up to "
                    + MAXIMUM_TERMS_PER_LIST + " terms, and each term may be up to " + MAXIMUM_TERM_LENGTH + " characters. "
                    + "Admins may replace another user's lists by passing that user's email as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The lists were replaced."),
            @ApiResponse(responseCode = "400", description = "The request body is malformed, a list has too many terms, or a term is too long."),
            @ApiResponse(responseCode = "401"),
            @ApiResponse(responseCode = "404")
    })
    @RequestMapping(value = "/api/redact-lists", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> replaceRedactLists(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody(required = false) String body,
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

        if (body == null || body.isBlank()) {
            return new ResponseEntity<>(new GenericResponse("A request body is required."), HttpStatus.BAD_REQUEST);
        }

        final RedactListsRequest request;
        try {
            request = gson.fromJson(body, RedactListsRequest.class);
        } catch (final JsonSyntaxException ex) {
            return new ResponseEntity<>(new GenericResponse("Malformed request body."), HttpStatus.BAD_REQUEST);
        }

        if (request == null) {
            return new ResponseEntity<>(new GenericResponse("A request body is required."), HttpStatus.BAD_REQUEST);
        }

        // Normalize then validate each list. A null/omitted field clears that list.
        final List<String> alwaysRedact = normalize(request.getAlwaysRedact());
        final List<String> neverRedact = normalize(request.getNeverRedact());

        final String alwaysError = validate(alwaysRedact, "always-redact");
        if (alwaysError != null) {
            return new ResponseEntity<>(new GenericResponse(alwaysError), HttpStatus.BAD_REQUEST);
        }
        final String neverError = validate(neverRedact, "never-redact");
        if (neverError != null) {
            return new ResponseEntity<>(new GenericResponse(neverError), HttpStatus.BAD_REQUEST);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId, "replace redact lists");

        // saveOrUpdate replaces both lists for the user (insert if absent), and audits the change.
        redactListsService.saveOrUpdate(requestId, userId, alwaysRedact, neverRedact, Source.API.getSource());

        return new ResponseEntity<>(
                new GenericResponse("Redact lists updated. always-redact: " + alwaysRedact.size()
                        + ", never-redact: " + neverRedact.size() + "."),
                HttpStatus.OK);

    }

    @Operation(summary = "Append to the always-redact and never-redact lists.",
            description = "Appends the supplied terms to the existing lists rather than replacing them - use POST to "
                    + "replace. Each field's terms are added to the current contents of that list; a list that is "
                    + "omitted or sent as an empty array is left unchanged. Terms are trimmed, blank entries are dropped, "
                    + "and terms already present are not added again. The resulting list may contain up to "
                    + MAXIMUM_TERMS_PER_LIST + " terms, and each term may be up to " + MAXIMUM_TERM_LENGTH + " characters; "
                    + "an append that would exceed the limit is rejected. Admins may append to another user's lists by "
                    + "passing that user's email as owner.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The terms were appended."),
            @ApiResponse(responseCode = "400", description = "The request body is malformed, the resulting list has too many terms, or a term is too long."),
            @ApiResponse(responseCode = "401"),
            @ApiResponse(responseCode = "404")
    })
    @RequestMapping(value = "/api/redact-lists", method = RequestMethod.PUT, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GenericResponse> appendRedactLists(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody(required = false) String body,
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

        if (body == null || body.isBlank()) {
            return new ResponseEntity<>(new GenericResponse("A request body is required."), HttpStatus.BAD_REQUEST);
        }

        final RedactListsRequest request;
        try {
            request = gson.fromJson(body, RedactListsRequest.class);
        } catch (final JsonSyntaxException ex) {
            return new ResponseEntity<>(new GenericResponse("Malformed request body."), HttpStatus.BAD_REQUEST);
        }

        if (request == null) {
            return new ResponseEntity<>(new GenericResponse("A request body is required."), HttpStatus.BAD_REQUEST);
        }

        // Start from the user's current lists; a missing document means empty lists.
        final RedactListsEntity existing = redactListsService.find(userId);
        final List<String> currentAlways = existing != null ? existing.getTermsToAlwaysRedact() : List.of();
        final List<String> currentNever = existing != null ? existing.getTermsToNeverRedact() : List.of();

        // Append the incoming terms (a null/omitted field appends nothing, leaving that list unchanged).
        final List<String> alwaysRedact = append(currentAlways, normalize(request.getAlwaysRedact()));
        final List<String> neverRedact = append(currentNever, normalize(request.getNeverRedact()));

        // Validate the resulting lists so an append cannot push a list past its limits.
        final String alwaysError = validate(alwaysRedact, "always-redact");
        if (alwaysError != null) {
            return new ResponseEntity<>(new GenericResponse(alwaysError), HttpStatus.BAD_REQUEST);
        }
        final String neverError = validate(neverRedact, "never-redact");
        if (neverError != null) {
            return new ResponseEntity<>(new GenericResponse(neverError), HttpStatus.BAD_REQUEST);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId, "append redact lists");

        redactListsService.saveOrUpdate(requestId, userId, alwaysRedact, neverRedact, Source.API.getSource());

        final int addedAlways = alwaysRedact.size() - currentAlways.size();
        final int addedNever = neverRedact.size() - currentNever.size();

        return new ResponseEntity<>(
                new GenericResponse("Redact lists updated. Appended " + addedAlways + " to always-redact (now "
                        + alwaysRedact.size() + "), " + addedNever + " to never-redact (now " + neverRedact.size() + ")."),
                HttpStatus.OK);

    }

    /** Returns the existing terms followed by the incoming terms that are not already present (exact match). */
    private static List<String> append(final List<String> existing, final List<String> incoming) {
        final List<String> combined = new ArrayList<>(existing);
        for (final String term : incoming) {
            if (!combined.contains(term)) {
                combined.add(term);
            }
        }
        return combined;
    }

    /** Trims each term and drops null/blank entries. A null input list becomes an empty list. */
    private static List<String> normalize(final List<String> terms) {
        if (terms == null) {
            return new ArrayList<>();
        }
        final List<String> normalized = new ArrayList<>(terms.size());
        for (final String term : terms) {
            if (term == null) {
                continue;
            }
            final String trimmed = term.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    /** Returns an error message if the normalized list violates the size/length limits, else null. */
    private static String validate(final List<String> terms, final String listName) {
        if (terms.size() > MAXIMUM_TERMS_PER_LIST) {
            return "The " + listName + " list has too many terms. The maximum is " + MAXIMUM_TERMS_PER_LIST + ".";
        }
        for (final String term : terms) {
            if (term.length() > MAXIMUM_TERM_LENGTH) {
                return "A term in the " + listName + " list is too long. Terms cannot exceed " + MAXIMUM_TERM_LENGTH + " characters.";
            }
        }
        return null;
    }

}
