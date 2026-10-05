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
import ai.philterd.philter.api.requests.CreateUserRequest;
import ai.philterd.philter.api.requests.SetUserRoleRequest;
import ai.philterd.philter.api.responses.CreatedUserResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetUsersResponse;
import ai.philterd.philter.api.responses.UserResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Manages users. Every endpoint requires an administrator as well as
 * its scope, except {@code GET /api/users/me}, which any key holding {@code users:read} may call.
 *
 * <p>Users have no password here: they authenticate with API keys, so no endpoint accepts or returns
 * a password, password hash, or MFA secret.
 */
@Tag(name = "Users",
        description = "Create and manage users. Requires an administrator, "
                + "except for reading the calling key's own user.")
@Controller
public class UsersApiController extends AbstractApiController {

    /** Reserved so {@code GET /api/users/me} can never be mistaken for a user named "me". */
    private static final String SELF = "me";

    private final UserService userService;
    private final PolicyDataService policyDataService;
    private final ContextDataService contextDataService;

    public UsersApiController(final ApiKeyDataService apiKeyDataService,
                              final ApiKeyCache apiKeyCache,
                              final UserService userService,
                              final PolicyDataService policyDataService,
                              final ContextDataService contextDataService) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.policyDataService = policyDataService;
        this.contextDataService = contextDataService;
    }

    @Operation(
            summary = "List users.",
            description = "Lists users sorted by username, including deactivated users, with the total count. "
                    + "Requires an administrator as well as the scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of users.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GetUsersResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:read, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_READ)
    @RequestMapping(value = "/api/users", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getUsers(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Listing users");
        if (refusal != null) {
            return refusal;
        }

        final List<UserResponse> users = new ArrayList<>();
        for (final UserEntity user : userService.findAll(normalizeOffset(offset), normalizeLimit(limit))) {
            users.add(new UserResponse(user));
        }

        return ResponseEntity.ok(new GetUsersResponse(users, userService.count()));

    }

    @Operation(
            summary = "Get the calling key's user.",
            description = "Returns the user that owns the API key making the request. Does not require an "
                    + "administrator.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The calling key's user.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:read.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_READ)
    @RequestMapping(value = "/api/users/" + SELF, method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getCurrentUser(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final UserEntity user = userService.findOneById(apiKeyEntity.getUserId());
        if (user == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    @Operation(
            summary = "Get a user.",
            description = "Returns one user by username, active or deactivated. Requires an administrator as "
                    + "well as the scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The user.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:read, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.USERS_READ)
    @RequestMapping(value = "/api/users/{username}", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getUser(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @PathVariable("username") String username) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Reading a user");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    @Operation(
            summary = "Create a user.",
            description = "Creates a user with a default policy and context. The role is user unless admin is "
                    + "given. The user has no password and authenticates with API keys; create one with "
                    + "POST /api/users/{username}/api-keys. Requires an administrator as well as the scope. "
                    + "Recorded as a user_created audit event naming the calling administrator as the principal and "
                    + "the calling API key in the details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The user was created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedUserResponse.class))),
            @ApiResponse(responseCode = "400", description = "The username is missing or reserved, the role is not user or admin, or a password was sent."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "A user with that username already exists, active or deactivated.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> createUser(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody CreateUserRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Creating a user");
        if (refusal != null) {
            return refusal;
        }

        final String username = request.getUsername() == null ? null : request.getUsername().trim();
        if (username == null || username.isBlank()) {
            throw new BadRequestException("username is required.");
        }
        if (SELF.equalsIgnoreCase(username)) {
            throw new BadRequestException("'" + SELF + "' is reserved and cannot be a username.");
        }
        if (request.getPassword() != null) {
            throw new BadRequestException("password is not accepted. Users authenticate with API keys.");
        }

        final String role = request.getRole() == null ? UserService.ROLE_USER : normalizeRole(request.getRole());

        final ServiceResponse response = userService.createUser(requestId, username, request.getEmail(), role, policyDataService,
                contextDataService, Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());

        if (!response.isSuccessful()) {
            // The only way creation fails is a username that is already taken, by an active account or
            // by a deactivated one holding the name in reserve.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedUserResponse(username, role));

    }

    @Operation(
            summary = "Set a user's role.",
            description = "Sets the role to user or admin. The last active administrator cannot be demoted. "
                    + "Requires an administrator as well as the scope. Recorded as a user_role_changed audit event "
                    + "naming the calling administrator as the principal and the calling API key in the details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The role was set.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "The role is missing or is not user or admin."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user is the last active administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/role", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setUserRole(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username,
            final @RequestBody SetUserRoleRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Setting a user's role");
        if (refusal != null) {
            return refusal;
        }

        if (request.getRole() == null) {
            throw new BadRequestException("role is required.");
        }
        final String role = normalizeRole(request.getRole());

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final ServiceResponse response = userService.setUserRole(requestId, user, role,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    @Operation(
            summary = "Deactivate a user.",
            description = "Deactivates a user: the user's API keys stop working, and the user and all of its "
                    + "data are retained so it can be reactivated. The last active administrator, and the calling "
                    + "administrator, cannot be deactivated. Requires an administrator as well as the scope. "
                    + "Recorded as a user_deactivated audit event naming the calling administrator as the principal "
                    + "and the calling API key in the details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The user was deactivated.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user is already deactivated, is the caller, or is the last active administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/deactivate", method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> deactivateUser(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Deactivating a user");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // The request would end with the caller locked out by their own key.
        if (user.getId().equals(apiKeyEntity.getUserId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new GenericResponse("An administrator cannot deactivate their own user."));
        }

        final ServiceResponse response = userService.deactivateUser(requestId, user,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    @Operation(
            summary = "Reactivate a user.",
            description = "Reactivates a deactivated user, restoring its API keys. Requires an administrator as "
                    + "well as the scope. Recorded as a user_reactivated audit event naming the calling "
                    + "administrator as the principal and the calling API key in the details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The user was reactivated.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user is already active.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/reactivate", method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> reactivateUser(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Reactivating a user");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final ServiceResponse response = userService.reactivateUser(requestId, user,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    private static String normalizeRole(final String role) {
        final String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (!UserService.ROLE_USER.equals(normalized) && !UserService.ROLE_ADMIN.equals(normalized)) {
            throw new BadRequestException("role must be " + UserService.ROLE_USER + " or " + UserService.ROLE_ADMIN + ".");
        }
        return normalized;
    }

}
