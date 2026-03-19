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
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetContextResponse;
import ai.philterd.philter.api.responses.GetContextsResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.ContextEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Tag(name = "Contexts", description = "Operations for creating and managing contexts.")
@Controller
public class ContextsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextsApiController.class);

    private final ContextDataService contextService;
    private final ContextEntryDataService contextEntryService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public ContextsApiController(final ContextDataService contextService,
                                 final ContextEntryDataService contextEntryService,
                                 final ApiKeyDataService apiKeyDataService,
                                 final AuditEventPublisher auditEventPublisher,
                                 final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.contextService = contextService;
        this.contextEntryService = contextEntryService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
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

        final ObjectId userId = apiKeyEntity.getId();

        final List<ContextEntity> contextEntities = contextService.findAll(userId);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXTS_RETRIEVED, apiKeyEntity.getId(), getClientIpAddress(httpServletRequest));

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

        final ObjectId userId = apiKeyEntity.getId();

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
            @ApiResponse(responseCode = "400", description = "The context could not be created.")
    })
    @RequestMapping(value = "/api/contexts", method = RequestMethod.POST)
    public ResponseEntity<GenericResponse> createContext(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam("name") String name,
            final @RequestParam(value = "coref", required = false, defaultValue = "false") boolean coref,
            final @RequestParam(value = "disambiguation", required = false, defaultValue = "false") boolean disambiguation,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = apiKeyEntity.getId();

        final ServiceResponse serviceResponse = contextService.create(name, userId, coref, disambiguation);

        if(serviceResponse.isSuccessful()) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_CREATED, apiKeyEntity.getId(), getClientIpAddress(httpServletRequest));
            return new ResponseEntity<>(new GenericResponse("Context created."), HttpStatus.OK);

        } else {

            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.BAD_REQUEST);

        }

    }

    @Operation(summary = "Delete a context.", description = "Delete an existing context.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The context was deleted."),
            @ApiResponse(responseCode = "400", description = "The context could not be deleted.")
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

        final ObjectId userId = apiKeyEntity.getId();

        final ServiceResponse serviceResponse = contextService.deleteByName(name, userId);

        if(serviceResponse.isSuccessful()) {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CONTEXT_DELETED, apiKeyEntity.getId(), getClientIpAddress(httpServletRequest));
            return new ResponseEntity<>(new GenericResponse("Context deleted."), HttpStatus.OK);

        } else {

            return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.BAD_REQUEST);

        }

    }

}
