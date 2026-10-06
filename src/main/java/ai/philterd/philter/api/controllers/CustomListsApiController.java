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
import ai.philterd.philter.api.responses.CustomListConflictResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetListsResponse;
import ai.philterd.philter.api.responses.ListSummaryResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.CustomListEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.UserService;
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

import static ai.philterd.philter.data.services.CustomListDataService.MAXIMUM_ITEM_LENGTH;
import static ai.philterd.philter.data.services.CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS;

@Tag(name = "Custom Lists", description = "Operations for creating and managing custom lists.")
@Controller
public class CustomListsApiController extends AbstractApiController {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomListsApiController.class);

    private final CustomListDataService customListService;
    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final Gson gson;

    public CustomListsApiController(final CustomListDataService customListService, final UserService userService,
                                    final ApiKeyDataService apiKeyDataService,
                                    final AuditEventPublisher auditEventPublisher,
                                    final ApiKeyCache apiKeyCache, final Gson gson) {
        super(apiKeyDataService, apiKeyCache);
        this.customListService = customListService;
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.gson = gson;
    }

    @Operation(summary = "List custom lists.",
            description = "Lists the caller's custom lists, all of them, each with its name, description, and number of "
                    + "items. Admins may list another user's lists with owner, or every user's with all_users=true, which "
                    + "is paged with offset and limit, adds each list's owner, and requires ADMIN_CROSS_USER_ACCESS_ENABLED.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Each list's name, description, and size; with all_users, its owner too. The schema is set in ApiDocumentationConfig."),
            @ApiResponse(responseCode = "400", description = "Both owner and all_users were given."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts. Also returned for all_users when the caller is not an administrator or cross-user access is disabled.")
    })
    @RequiresScope(ApiKeyScope.LISTS_READ)
    @RequestMapping(value = "/api/lists", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getLists(
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

        if (allUsers) {
            if (!mayListAllUsers(userService, apiKeyEntity.getUserId(), owner)) {
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            // Paged only here: the per-user listing has always returned every list, so a default page
            // size there would silently cut off existing callers.
            final List<CustomListEntity> lists =
                    customListService.findAllAcrossUsers(normalizeOffset(offset), normalizeLimit(limit));
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LISTS_RETRIEVED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));
            auditAllUsersListing(auditEventPublisher, requestId, apiKeyEntity.getUserId(), "list custom lists");
            final Map<ObjectId, String> owners = ownerNames(userService, lists, CustomListEntity::getUserId);
            final List<ListSummaryResponse> summaries = new ArrayList<>(lists.size());
            for (final CustomListEntity entity : lists) {
                summaries.add(summary(entity, owners.get(entity.getUserId())));
            }
            return new ResponseEntity<>(gson.toJson(summaries), HttpStatus.OK);
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        final List<CustomListEntity> customListEntities = customListService.findAll(userId);

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LISTS_RETRIEVED, apiKeyEntity.getUserId(), getClientIpAddress(httpServletRequest));

        final List<ListSummaryResponse> lists = new ArrayList<>(customListEntities.size());
        for (final CustomListEntity customListEntity : customListEntities) {
            // No owner: Gson leaves out a null field, so a per-user listing does not carry one.
            lists.add(summary(customListEntity, null));
        }

        return new ResponseEntity<>(gson.toJson(lists), HttpStatus.OK);

    }

    private static ListSummaryResponse summary(final CustomListEntity entity, final String owner) {
        return new ListSummaryResponse(entity.getName(), entity.getDescription(),
                entity.getItems() == null ? 0 : entity.getItems().size(), owner);
    }

    @Operation(summary = "Get the contents of a list.", description = "Gets a list's items, in the lists field, and its description.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200"),
            @ApiResponse(responseCode = "404", description = "The list does not exist, or the owner does not exist or may not be reached. The API does not distinguish these, so a name or owner cannot be used to discover what exists."),
    })
    @RequiresScope(ApiKeyScope.LISTS_READ)
    @RequestMapping(value = "/api/lists/{name}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<GetListsResponse> getLists(
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

        final CustomListEntity customListEntity = customListService.findOneByName(name, userId);

        if(customListEntity == null) {

            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        } else {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_ITEMS_RETRIEVED, apiKeyEntity.getUserId(), customListEntity.getId(), getClientIpAddress(httpServletRequest));

            final GetListsResponse getListsResponse = new GetListsResponse(customListEntity.getItems(),
                    customListEntity.getDescription());

            return new ResponseEntity<>(getListsResponse, HttpStatus.OK);

        }

    }

    @Operation(summary = "Create a list.", description = "Creates a list whose items are the request body. A name the "
            + "owner already uses is refused with 409; replace an existing list with PUT /api/lists/{name}. description "
            + "is optional and defaults to an empty string.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The list was created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "400", description = "The list name is empty or " + PathSafeNames.RULE + ", the list contains too many items (maximum " + MAXIMUM_NUMBER_OF_ITEMS + "), or an item is too long (maximum " + MAXIMUM_ITEM_LENGTH + " characters)."),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it. The API does not distinguish the two, so an owner value cannot be used to discover accounts.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "The list was not created because the owner already has a list "
                    + "with this name; reason is list_exists. Nothing is changed.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CustomListConflictResponse.class)))
    })
    @RequiresScope(ApiKeyScope.LISTS_WRITE)
    @RequestMapping(value = "/api/lists/{name}", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> createList(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String list,
            final @Parameter(description = "The list's description. Defaults to an empty string.")
            @RequestParam(value = "description", required = false) String description,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody List<String> listItems,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        return write(authorizationHeader, list, description, owner, listItems, requestId, httpServletRequest, false);

    }

    @Operation(summary = "Replace a list.", description = "Replaces the items of an existing list with the request body. "
            + "description is optional: left out, the list keeps its description; given as an empty string, it is cleared.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The list was replaced.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "400", description = "The list contains too many items (maximum " + MAXIMUM_NUMBER_OF_ITEMS + "), or an item is too long (maximum " + MAXIMUM_ITEM_LENGTH + " characters)."),
            @ApiResponse(responseCode = "404", description = "There is no such list, or the owner does not exist or the caller may not reach it.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.LISTS_WRITE)
    @RequestMapping(value = "/api/lists/{name}", method = RequestMethod.PUT, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> replaceList(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String list,
            final @Parameter(description = "The list's description. Left out, the list keeps its own; an empty string clears it.")
            @RequestParam(value = "description", required = false) String description,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody List<String> listItems,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {

        return write(authorizationHeader, list, description, owner, listItems, requestId, httpServletRequest, true);

    }

    /** Creates or replaces a list; neither falls back to the other, so a create never overwrites. */
    private ResponseEntity<Object> write(final String authorizationHeader, final String list, final String description,
                                         final String owner, final List<String> listItems, final String requestId,
                                         final HttpServletRequest httpServletRequest, final boolean replace) {

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);

        if(apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ObjectId userId = resolveTargetUserId(userService, apiKeyEntity.getUserId(), owner);
        if (userId == null) {
            return new ResponseEntity<>(new GenericResponse("Not found."), HttpStatus.NOT_FOUND);
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, apiKeyEntity.getUserId(), userId,
                (replace ? "replace" : "create") + " custom list '" + list + "'");

        final String origin = getClientIpAddress(httpServletRequest);
        final ServiceResponse serviceResponse = replace
                ? customListService.replace(requestId, userId, list, description, listItems, origin)
                : customListService.saveOrUpdate(requestId, userId, list, description, listItems, false, origin);

        if (serviceResponse.getStatusCode() == HttpStatus.CONFLICT.value()) {
            return new ResponseEntity<>(new CustomListConflictResponse(serviceResponse.getMessage(),
                    serviceResponse.getDetails()), HttpStatus.CONFLICT);
        }
        return new ResponseEntity<>(new GenericResponse(serviceResponse.getMessage()), HttpStatus.valueOf(serviceResponse.getStatusCode()));

    }

    @Operation(summary = "Delete a list by name in the query.",
            description = "Deletes a list named in the name query parameter, for a list whose name cannot be used in "
                    + "a request path. Otherwise the same as DELETE /api/lists/{name}.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The list was deleted."),
            @ApiResponse(responseCode = "404", description = "The given list does not exist.")
    })
    @RequiresScope(ApiKeyScope.LISTS_WRITE)
    @RequestMapping(value = "/api/lists", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteListNamedInQuery(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam("name") String list,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestAttribute("requestId") String requestId,
            final HttpServletRequest httpServletRequest) {
        return deleteList(authorizationHeader, list, owner, requestId, httpServletRequest);
    }

    @Operation(summary = "Delete a list.", description = "Delete a list with the given name.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The list was deleted."),
            @ApiResponse(responseCode = "404", description = "The given list does not exist.")
    })
    @RequiresScope(ApiKeyScope.LISTS_WRITE)
    @RequestMapping(value = "/api/lists/{name}", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> deleteList(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("name") String list,
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
                "delete custom list '" + list + "'");

        // See if this list exists.
        final CustomListEntity customListEntity = customListService.findOneByName(list, userId);

        if(customListEntity == null) {

            return new ResponseEntity<>(HttpStatus.NOT_FOUND);

        } else {

            auditEventPublisher.auditEvent(requestId, AuditLogEvent.CUSTOM_LIST_DELETED, customListEntity.getId(), getClientIpAddress(httpServletRequest));

            customListService.deleteByName(list, userId);

            return new ResponseEntity<>(HttpStatus.NO_CONTENT);

        }

    }

}