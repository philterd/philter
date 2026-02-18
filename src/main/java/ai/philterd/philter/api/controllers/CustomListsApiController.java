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

import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetListsResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.services.cache.ApiKeyCache;
import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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

import static ai.philterd.philter.data.services.CustomListDataService.MAXIMUM_ITEM_LENGTH;
import static ai.philterd.philter.data.services.CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS;

@Tag(name = "Custom Lists", description = "Operations for creating and managing custom lists.")
@Controller
public class CustomListsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomListsApiController.class);

    private final CustomListDataService customListService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public CustomListsApiController(final CustomListDataService customListService, final ApiKeyDataService apiKeyDataService,
                                    final AuditEventPublisher auditEventPublisher,
                                    final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.customListService = customListService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "Get the names of existing lists.", description = "Get the names of existing lists.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200")
    })
    @RequestMapping(value = "/api/lists", method = RequestMethod.GET)
    public ResponseEntity<String> getLists(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        final List<CustomListEntity> customListEntities = customListService.findAll();

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LISTS_RETRIEVED, null, getClientIpAddress(httpServletRequest));

        final List<String> lists = new ArrayList<>();

        for(final CustomListEntity customListEntity : customListEntities) {
            lists.add(customListEntity.getName());
        }

        return new ResponseEntity<>(gson.toJson(lists), HttpStatus.OK);

    }

    @Operation(summary = "Get the contents of a list.", description = "Get the contents of a list with the provided name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "409", description = "A list with the given does not exist."),
    })
    @RequestMapping(value = "/api/lists/{name}", method = RequestMethod.GET)
    public ResponseEntity<GetListsResponse> getLists(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String name,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        final CustomListEntity customListEntity = customListService.findOneByName(name);

        if(customListEntity == null) {

            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        } else {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_ITEMS_RETRIEVED, null, customListEntity.getId(), getClientIpAddress(httpServletRequest));

            final GetListsResponse getListsResponse = new GetListsResponse(customListEntity.getItems());

            return new ResponseEntity<>(getListsResponse, HttpStatus.OK);

        }

    }

    @Operation(summary = "Create a list.", description = "Create a list whose contents are the request body.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The list was updated or created."),
            @ApiResponse(responseCode = "400", description = "The list name is empty, the list contains too many items (maximum " + MAXIMUM_NUMBER_OF_ITEMS + "), or an item is too long (maximum " + MAXIMUM_ITEM_LENGTH + " characters)."),
            @ApiResponse(responseCode = "409", description = "A list with the given name already exists."),
            @ApiResponse(responseCode = "412", description = "The maximum number of lists already exists.")
    })
    @RequestMapping(value = "/api/lists/{list}", method = RequestMethod.POST)
    public ResponseEntity<GenericResponse> createList(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("list") String list,
            final @RequestParam(value = "description", defaultValue="", required = false) String description,
            final @RequestBody List<String> listItems,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        final ServiceResponse serviceResponse = customListService.saveOrUpdate(requestId,list, description, listItems, true, getClientIpAddress(httpServletRequest));

        return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.valueOf(serviceResponse.getStatusCode()));

    }

    @Operation(summary = "Delete a list.", description = "Delete a list with the given name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The list was deleted."),
            @ApiResponse(responseCode = "404", description = "The given list does not exist.")
    })
    @RequestMapping(value = "/api/lists/{list}", method = RequestMethod.DELETE)
    public ResponseEntity<String> deleteList(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("list") String list,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

         final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        // See if this list exists.
        final CustomListEntity customListEntity = customListService.findOneByName(list);

        if(customListEntity == null) {

            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        } else {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_DELETED, customListEntity.getId(), getClientIpAddress(httpServletRequest));

            customListService.deleteByName(list);

            return new ResponseEntity<>(HttpStatus.NO_CONTENT);

        }

    }

}