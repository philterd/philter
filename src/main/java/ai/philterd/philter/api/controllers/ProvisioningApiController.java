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
import ai.philterd.philter.api.requests.CreateApiKeyRequest;
import ai.philterd.philter.api.requests.CreateUserRequest;
import ai.philterd.philter.api.responses.CreatedApiKeyResponse;
import ai.philterd.philter.api.responses.CreatedUserResponse;
import ai.philterd.philter.api.responses.GenericResponse;
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
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Creates users and API keys over the API, so a deployment can be provisioned without a person and a
 * browser.
 *
 * <p>Both endpoints exist only where {@code PROVISIONING_API_ENABLED} is set. With it unset they
 * answer {@code 404 Not Found}, which is checked before anything else here: the deployment did not
 * opt in, so there is nothing to say about who the caller is or what they asked for.
 *
 * <p>The dashboard remains the normal path, because creating a credential there forces the caller
 * through the login and whatever it requires, including MFA. These endpoints are the way around
 * that, so they are bounded: neither creates an administrator, neither grants a key a scope the
 * calling key does not itself hold, both require an administrator on top of the scope, and every
 * creation is audited with the acting principal.
 */
@Tag(name = "Provisioning",
        description = "Create users and API keys without the dashboard, for automation that stands up a "
                + "deployment. Present only when PROVISIONING_API_ENABLED=true; otherwise both endpoints "
                + "answer 404.")
@Controller
public class ProvisioningApiController extends AbstractApiController {

    /** Matches the dashboard's minimum, so a password the API accepts is one the login accepts. */
    private static final int MIN_PASSWORD_LENGTH = 16;

    /** The only role these endpoints create. Administrators are made in the dashboard. */
    private static final String PROVISIONED_ROLE = "user";

    private final UserService userService;
    private final PolicyDataService policyDataService;
    private final ContextDataService contextDataService;

    public ProvisioningApiController(final ApiKeyDataService apiKeyDataService,
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
            summary = "Create a user.",
            description = "Creates a non-administrator user with a default policy and context, as the dashboard "
                    + "does. The role is not a parameter: this endpoint cannot create an administrator, because an "
                    + "administrator account is made in the dashboard where the caller has passed the login and its "
                    + "MFA. Requires an administrator as well as the scope, and is present only when "
                    + "PROVISIONING_API_ENABLED=true. Recorded as a user_created audit event naming the calling "
                    + "administrator as the principal.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The user was created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedUserResponse.class))),
            @ApiResponse(responseCode = "400", description = "The username is missing, or the password is missing or shorter than 16 characters."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "PROVISIONING_API_ENABLED is not set, so the endpoint is not present.",
                    content = @Content),
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

        if (!isProvisioningApiEnabled()) {
            return notPresent();
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), "Creating a user");
        if (refusal != null) {
            return ResponseEntity.status(refusal.getStatusCode()).body(refusal.getBody());
        }

        final String username = request.getUsername() == null ? null : request.getUsername().trim();
        if (username == null || username.isBlank()) {
            throw new BadRequestException("username is required.");
        }
        if (request.getPassword() == null || request.getPassword().length() < MIN_PASSWORD_LENGTH) {
            throw new BadRequestException("password is required and must be at least "
                    + MIN_PASSWORD_LENGTH + " characters.");
        }

        final ServiceResponse response = userService.createUser(requestId, username, request.getEmail(),
                request.getPassword(), PROVISIONED_ROLE, policyDataService, contextDataService,
                Source.API.getSource(), false, apiKeyEntity.getUserId());

        if (!response.isSuccessful()) {
            // The only way creation fails is a username that is already taken, by an active account or
            // by a deactivated one holding the name in reserve.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage()));
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedUserResponse(username, PROVISIONED_ROLE));

    }

    @Operation(
            summary = "Create an API key for a user.",
            description = "Mints a key for the named user with the scopes given in the body, and returns its value "
                    + "once: only the hash is stored, so the response is the one chance to capture it. The scopes "
                    + "must be a subset of those the calling key holds, so this cannot be used to widen access "
                    + "beyond the credential making the request. Requires an administrator as well as the scope, "
                    + "and is present only when PROVISIONING_API_ENABLED=true. Recorded as an api_key_created audit "
                    + "event naming the calling administrator.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The key was created. The value is returned here and nowhere else.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedApiKeyResponse.class))),
            @ApiResponse(responseCode = "400", description = "No scopes were given, or one of them is not a scope."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold api-keys:write, the caller is not an administrator, or a requested scope is not held by the calling key.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "PROVISIONING_API_ENABLED is not set, so the endpoint is not present, or there is no active user with that username.",
                    content = @Content)
    })
    @RequiresScope(ApiKeyScope.API_KEYS_WRITE)
    @RequestMapping(value = "/api/users/{username}/api-keys", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> createApiKey(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username,
            final @RequestBody CreateApiKeyRequest request) {

        if (!isProvisioningApiEnabled()) {
            return notPresent();
        }

        final ApiKeyEntity apiKeyEntity = getApiKeyEntity(authorizationHeader);
        if (apiKeyEntity == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ResponseEntity<GenericResponse> refusal =
                authorizeAdminOnly(userService, apiKeyEntity.getUserId(), "Creating an API key");
        if (refusal != null) {
            return ResponseEntity.status(refusal.getStatusCode()).body(refusal.getBody());
        }

        final Set<String> scopes = requestedScopes(request);

        // Checked before the user is resolved: a caller who could not grant these scopes anyway must
        // not learn from the status code whether a username exists.
        final List<String> notHeld = new ArrayList<>();
        for (final String scope : scopes) {
            if (!apiKeyEntity.hasScope(ApiKeyScope.fromScope(scope))) {
                notHeld.add(scope);
            }
        }
        if (!notHeld.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                    "The calling API key does not hold: " + String.join(", ", notHeld)
                            + ". A key cannot grant a scope it does not carry."));
        }

        final UserEntity user = userService.findByUsername(username);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // The principal recorded is the new key and the object is the user it belongs to, as for a key
        // made in the dashboard; the administrator who asked for it goes in the details, because that
        // is the part a dashboard-created key does not have.
        final ServiceResponse response = apiKeyService.createApiKey(requestId, user.getId(),
                Source.API.getSource(), scopes,
                "created by user: " + apiKeyEntity.getUserId() + ", api_key: " + apiKeyEntity.getId()
                        + ", scopes: [" + String.join(", ", scopes) + "]");

        return ResponseEntity.status(HttpStatus.CREATED).body(
                new CreatedApiKeyResponse(user.getUsername(), response.getMessage(), new ArrayList<>(scopes)));

    }

    /**
     * The requested scopes, in the order given and without duplicates. An unrecognized name is refused
     * rather than dropped: a key silently narrower than the one that was asked for fails later, in the
     * integration it was minted for, rather than here.
     */
    private static Set<String> requestedScopes(final CreateApiKeyRequest request) {

        if (request.getScopes() == null || request.getScopes().isEmpty()) {
            throw new BadRequestException("scopes is required and must name at least one scope. "
                    + "A key with no scopes can call nothing.");
        }

        final Set<String> scopes = new LinkedHashSet<>();

        for (final String scope : request.getScopes()) {
            if (scope == null || ApiKeyScope.fromScope(scope.trim()) == null) {
                throw new BadRequestException("'" + scope + "' is not a scope.");
            }
            scopes.add(scope.trim());
        }

        return scopes;

    }

    /** The answer when the deployment has not opted in: the endpoint is not there. */
    private static ResponseEntity<Object> notPresent() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

}
