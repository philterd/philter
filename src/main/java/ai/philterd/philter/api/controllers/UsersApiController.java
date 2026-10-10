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
import ai.philterd.philter.api.requests.ChangePasswordRequest;
import ai.philterd.philter.api.requests.CreateUserRequest;
import ai.philterd.philter.api.requests.MfaCodeRequest;
import ai.philterd.philter.api.requests.SetPasswordRequest;
import ai.philterd.philter.api.requests.SetUserEmailRequest;
import ai.philterd.philter.api.requests.SetUserRoleRequest;
import ai.philterd.philter.api.responses.CreatedUserResponse;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.GetUsersResponse;
import ai.philterd.philter.api.responses.MfaEnrollmentResponse;
import ai.philterd.philter.api.responses.CurrentUserResponse;
import ai.philterd.philter.api.responses.UserResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.Listings;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.ErrorReasons;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.utils.PathSafeNames;
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
 * Manages users. Every endpoint requires an administrator as well as its scope, except
 * {@code GET /api/users/me} and {@code PUT /api/users/me/password}, which act on the calling key's own user.
 *
 * <p>A password is optional, for a person who signs in; a user without one can only use API keys. No
 * endpoint returns a password or its hash.
 */
@Tag(name = "Users",
        description = "Create and manage users and their passwords. Requires an administrator, "
                + "except for reading the calling key's own user and changing its own password.")
@Controller
public class UsersApiController extends AbstractApiController {

    /** Reserved so {@code GET /api/users/me} can never be mistaken for a user named "me". */
    static final String SELF = "me";

    private final UserService userService;
    private final PolicyDataService policyDataService;
    private final ContextDataService contextDataService;
    private final AdminSettingsDataService adminSettingsDataService;

    public UsersApiController(final ApiKeyDataService apiKeyDataService,
                              final ApiKeyCache apiKeyCache,
                              final UserService userService,
                              final PolicyDataService policyDataService,
                              final ContextDataService contextDataService,
                              final AdminSettingsDataService adminSettingsDataService) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.policyDataService = policyDataService;
        this.contextDataService = contextDataService;
        this.adminSettingsDataService = adminSettingsDataService;
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
            final @RequestParam(value = "q", required = false) String q,
            final @RequestParam(value = "role", required = false) String role,
            final @RequestParam(value = "active", required = false) Boolean active,
            final @RequestParam(value = "sort", required = false) String sort,
            final @RequestParam(value = "order", required = false) String order,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Listing users");
        if (refusal != null) {
            return refusal;
        }

        final String roleFilter = role == null || role.isBlank() ? null : normalizeRole(role);
        final Listings.Page<UserEntity> page = userService.list(q, roleFilter, active,
                listingSort(sort, order, USER_SORT, "username", false), normalizeOffset(offset), normalizeLimit(limit));

        final List<UserResponse> users = new ArrayList<>();
        for (final UserEntity user : page.items()) {
            users.add(new UserResponse(user));
        }

        return ResponseEntity.ok(new GetUsersResponse(users, page.total()));

    }

    /** The order a listing of users can take. The id carries the creation time. */
    private static final java.util.Map<String, String> USER_SORT = sortFields("username", "username", "created", "_id");

    @Operation(
            summary = "Get the calling key's user.",
            description = "Returns the user that owns the API key making the request, with whether this "
                    + "deployment makes MFA available and required. Does not require an administrator.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The calling key's user.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CurrentUserResponse.class))),
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

        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        return ResponseEntity.ok(new CurrentUserResponse(user,
                settings != null && settings.isMfaAvailable(), settings != null && settings.isMfaRequired()));

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
            throw new NotFoundException();
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    @Operation(
            summary = "Create a user.",
            description = "Creates a user with a default policy and context. The role is user unless admin is "
                    + "given. A password is optional; without one the user can only use API keys, created with "
                    + "POST /api/users/{username}/api-keys. A user created with a password must change it at next "
                    + "sign-in, and setting it is audited as user_password_set. Requires an administrator as well as the scope. "
                    + "Recorded as a user_created audit event naming the calling administrator as the principal and "
                    + "the calling API key in the details.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "The user was created.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreatedUserResponse.class))),
            @ApiResponse(responseCode = "400", description = "The username is missing, is reserved, or is not usable in a request "
                    + "path (it " + PathSafeNames.RULE + "), the role is not user or admin, or the password is "
                    + "shorter than 16 characters or longer than 72 bytes in UTF-8, or the email address is not valid."),
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
            throw new BadRequestException("username is required.", "username");
        }
        if (SELF.equalsIgnoreCase(username)) {
            throw new BadRequestException("'" + SELF + "' is reserved and cannot be a username.", "username");
        }
        if (!PathSafeNames.isPathSafe(username)) {
            // Every per-user route addresses the user by path.
            throw new BadRequestException("The username " + PathSafeNames.RULE + ".", "username");
        }
        if (request.getPassword() != null && UserService.passwordProblem(request.getPassword()) != null) {
            throw new BadRequestException(UserService.passwordProblem(request.getPassword()), "password");
        }
        if (UserService.emailProblem(request.getEmail()) != null) {
            throw new BadRequestException(UserService.emailProblem(request.getEmail()), "email");
        }

        final String role = request.getRole() == null ? UserService.ROLE_USER : normalizeRole(request.getRole());

        final ServiceResponse response = userService.createUser(requestId, username, request.getEmail(), role,
                request.getPassword(), policyDataService, contextDataService, Source.API.getSource(),
                apiKeyEntity.getUserId(), apiKeyEntity.getId());

        if (!response.isSuccessful()) {
            // The only way creation fails is a username that is already taken, by an active account or
            // by a deactivated one holding the name in reserve.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        final UserEntity created = userService.findAnyByUsername(username);
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedUserResponse(
                created == null ? null : created.getId().toHexString(), username, role));

    }

    @Operation(
            summary = "Change the calling user's password.",
            description = "Changes the password of the calling key's own user, which requires the current password. "
                    + "The new password must differ from it. Clears any required change and revokes the user's session "
                    + "keys, including the calling key if it is one; long-lived keys are unaffected. Requires the "
                    + "scope, not an administrator. Recorded as a user_password_changed audit event, without either "
                    + "password.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The password was changed.", content = @Content),
            @ApiResponse(responseCode = "400", description = "A password is missing, the new password is shorter than 16 "
                    + "characters or longer than 72 bytes in UTF-8, or it is the same as the current one.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the current password is not correct.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "The user has no password yet, so an administrator sets the "
                    + "first one, or the password was changed by a concurrent request.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/" + SELF + "/password", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> changeOwnPassword(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody ChangePasswordRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        if (request.getCurrentPassword() == null || request.getNewPassword() == null) {
            throw new BadRequestException("currentPassword and newPassword are required.");
        }

        final UserEntity user = userService.findOneById(apiKeyEntity.getUserId());
        if (user == null) {
            throw new UnauthorizedException("Unauthorized.");
        }

        final ServiceResponse response = userService.changeOwnPassword(requestId, user, request.getCurrentPassword(),
                request.getNewPassword(), Source.API.getSource(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        apiKeyService.revokeSessionKeys(requestId, user.getId(), Source.API.getSource(), "reason: password changed");

        return ResponseEntity.noContent().build();

    }

    @Operation(
            summary = "Set or reset a user's password.",
            description = "Sets another user's password without the current one. The user must change it at next "
                    + "sign-in, and their session keys are revoked. On the calling administrator's own user, this sets "
                    + "only the first password, with no change required, which is how the admin user gets one with the "
                    + "bootstrap API key; after that, use PUT /api/users/me/password. Requires an administrator as "
                    + "well as the scope. Recorded as a user_password_reset audit event when it replaces a password, "
                    + "or user_password_set when the user had none, naming the calling administrator and API key, "
                    + "never the password.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The password was set.", content = @Content),
            @ApiResponse(responseCode = "400", description = "The password is missing, shorter than 16 characters, or "
                    + "longer than 72 bytes in UTF-8.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user is the caller and already has a password, or the "
                    + "password was changed by a concurrent request.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/password", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setPassword(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username,
            final @RequestBody SetPasswordRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Setting another user's password");
        if (refusal != null) {
            return refusal;
        }

        if (request.getPassword() == null) {
            throw new BadRequestException("password is required.", "password");
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            throw new NotFoundException();
        }

        // Without this, a stolen session key could replace its own user's password without knowing it.
        final boolean self = user.getId().equals(apiKeyEntity.getUserId());
        if (self && user.getPassword() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "Change your own password with PUT /api/users/me/password, which requires the current one.",
                    ErrorReasons.SELF_ACTION_REFUSED));
        }

        final ServiceResponse response = userService.setPassword(requestId, user, request.getPassword(), !self,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        apiKeyService.revokeSessionKeys(requestId, user.getId(), Source.API.getSource(),
                "reason: password set by " + apiKeyEntity.getUserId() + ", api_key: " + apiKeyEntity.getId());

        return ResponseEntity.noContent().build();

    }

    @Operation(
            summary = "Start MFA enrollment.",
            description = "Generates a TOTP secret for the calling key's own user and returns it, with an otpauth:// URI "
                    + "to show as a QR code. The secret is returned only here and is encrypted at rest. Enrollment takes "
                    + "effect only once POST /api/users/me/mfa/confirm sees a valid code; starting again replaces an "
                    + "unconfirmed secret. Requires the mfaAvailable setting, and the scope but not an administrator.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The secret to set up an authenticator app with.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = MfaEnrollmentResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "MFA is not available on this deployment, or the user is already enrolled.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/" + SELF + "/mfa", method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> startMfaEnrollment(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {

        final UserEntity user = callingUser(requireApiKey(authorizationHeader));

        if (!mfaAvailable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "MFA is not available. An administrator turns it on with the mfaAvailable setting.", ErrorReasons.MFA_UNAVAILABLE));
        }

        final String secret = userService.startMfaEnrollment(user);
        if (secret == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse("MFA is already enrolled.",
                    ErrorReasons.MFA_ALREADY_ENROLLED));
        }

        return ResponseEntity.ok(new MfaEnrollmentResponse(secret, userService.mfaOtpauthUri(user, secret)));

    }

    @Operation(
            summary = "Confirm MFA enrollment.",
            description = "Completes enrollment with a code from the authenticator app for the secret POST "
                    + "/api/users/me/mfa returned. From then on, sign-in asks for a code. Revokes the user's session "
                    + "keys, so the person signs in again with MFA. Requires the scope but not an administrator. "
                    + "Recorded as a user_mfa_enrolled audit event.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "MFA is enrolled.", content = @Content),
            @ApiResponse(responseCode = "400", description = "The code is missing or not valid.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "MFA is not available, the user is already enrolled, or no enrollment was started.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/" + SELF + "/mfa/confirm", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> confirmMfaEnrollment(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody MfaCodeRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);
        final UserEntity user = callingUser(apiKeyEntity);

        if (!mfaAvailable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "MFA is not available. An administrator turns it on with the mfaAvailable setting.", ErrorReasons.MFA_UNAVAILABLE));
        }
        if (request.getCode() == null) {
            throw new BadRequestException("code is required.", "code");
        }

        final ServiceResponse response = userService.confirmMfaEnrollment(requestId, user, request.getCode(),
                Source.API.getSource(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        apiKeyService.revokeSessionKeys(requestId, user.getId(), Source.API.getSource(), "reason: MFA enrolled");

        return ResponseEntity.noContent().build();

    }

    @Operation(
            summary = "Remove your own MFA enrollment.",
            description = "Removes the calling key's own user's MFA, which takes a valid code so a stolen session key "
                    + "cannot turn MFA off. A bad code counts toward the lock. A user who has lost the device asks an "
                    + "administrator, who uses DELETE /api/users/{username}/mfa. Requires the scope but not an "
                    + "administrator. Recorded as a user_mfa_removed audit event.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "MFA was removed.", content = @Content),
            @ApiResponse(responseCode = "400", description = "The code is missing."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, the code is not valid, or the user's MFA is locked.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "409", description = "The user is not enrolled.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/" + SELF + "/mfa/remove", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> removeOwnMfa(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody MfaCodeRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);
        final UserEntity user = callingUser(apiKeyEntity);

        if (request.getCode() == null) {
            throw new BadRequestException("code is required.", "code");
        }
        if (!user.isMfaEnabled()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse("MFA is not enrolled.",
                    ErrorReasons.MFA_NOT_ENROLLED));
        }

        return switch (userService.checkMfaCode(requestId, user, request.getCode(), Source.API.getSource())) {
            case ACCEPTED -> {
                final ServiceResponse response = userService.removeMfa(requestId, user, Source.API.getSource(),
                        user.getId(), apiKeyEntity.getId());
                yield response.isSuccessful() ? ResponseEntity.noContent().build()
                        : ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage(), response.getDetails()));
            }
            case LOCKED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                    "MFA is locked after repeated bad codes. An administrator must unlock it.", ErrorReasons.MFA_LOCKED));
            case REFUSED -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse("The code is not valid.",
                    ErrorReasons.INVALID_CODE));
        };

    }

    @Operation(
            summary = "Remove a user's MFA enrollment.",
            description = "Removes another user's MFA, for a user who has lost their device, and clears any lock. On the "
                    + "caller's own user, use POST /api/users/me/mfa/remove, which takes a code. Requires an "
                    + "administrator as well as the scope. Recorded as a user_mfa_removed audit event naming the "
                    + "calling administrator and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "MFA was removed.", content = @Content),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user is the caller, or is not enrolled.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/mfa", method = RequestMethod.DELETE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> removeMfa(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Removing another user's MFA");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            throw new NotFoundException();
        }
        // Without this, a stolen administrator session key could turn off its own user's MFA without a code.
        if (user.getId().equals(apiKeyEntity.getUserId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(
                    "Remove your own MFA with POST /api/users/me/mfa/remove, which takes a code.",
                    ErrorReasons.SELF_ACTION_REFUSED));
        }

        final ServiceResponse response = userService.removeMfa(requestId, user, Source.API.getSource(),
                apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        return ResponseEntity.noContent().build();

    }

    @Operation(
            summary = "Unlock a user's MFA.",
            description = "Unlocks a user locked after five consecutive bad MFA codes and resets the count. Requires an "
                    + "administrator as well as the scope. Recorded as a user_mfa_unlocked audit event naming the "
                    + "calling administrator and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The user was unlocked.", content = @Content),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content),
            @ApiResponse(responseCode = "409", description = "The user is not locked.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/mfa/unlock", method = RequestMethod.POST,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> unlockMfa(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Unlocking a user's MFA");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            throw new NotFoundException();
        }

        final ServiceResponse response = userService.unlockMfa(requestId, user, Source.API.getSource(),
                apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(response.getStatusCode()).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        return ResponseEntity.noContent().build();

    }

    private UserEntity callingUser(final ApiKeyEntity apiKeyEntity) {
        final UserEntity user = userService.findOneById(apiKeyEntity.getUserId());
        if (user == null) {
            throw new UnauthorizedException("Unauthorized.");
        }
        return user;
    }

    private boolean mfaAvailable() {
        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        return settings != null && settings.isMfaAvailable();
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
            throw new BadRequestException("role is required.", "role");
        }
        final String role = normalizeRole(request.getRole());

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            throw new NotFoundException();
        }

        final ServiceResponse response = userService.setUserRole(requestId, user, role,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    @Operation(
            summary = "Set a user's email address.",
            description = "Sets or removes a user's email address. An address must have one @, something on each "
                    + "side, a dot in the domain, and no whitespace, and be at most " + UserService.MAX_EMAIL_LENGTH
                    + " characters; a null or empty email removes it. The email address is not how a user is "
                    + "addressed or signs in, so changing it changes nothing else. Requires an administrator as well "
                    + "as the scope. Recorded as a user_email_changed audit event naming the calling administrator as "
                    + "the principal and the calling API key in the details; the address itself is not recorded.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The email address was set or removed.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "The email address is not valid or too long.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold users:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "There is no user with that username.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.USERS_WRITE)
    @RequestMapping(value = "/api/users/{username}/email", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setUserEmail(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @PathVariable("username") String username,
            final @RequestBody SetUserEmailRequest request) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, apiKeyEntity, "Setting a user's email address");
        if (refusal != null) {
            return refusal;
        }

        final UserEntity user = userService.findAnyByUsername(username);
        if (user == null) {
            throw new NotFoundException();
        }

        final ServiceResponse response = userService.setEmail(requestId, user, request.getEmail(),
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            throw new BadRequestException(response.getMessage(), "email");
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
            throw new NotFoundException();
        }

        // The request would end with the caller locked out by their own key.
        if (user.getId().equals(apiKeyEntity.getUserId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(
                    new GenericResponse("An administrator cannot deactivate their own user.", ErrorReasons.SELF_ACTION_REFUSED));
        }

        final ServiceResponse response = userService.deactivateUser(requestId, user,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage(), response.getDetails()));
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
            throw new NotFoundException();
        }

        final ServiceResponse response = userService.reactivateUser(requestId, user,
                Source.API.getSource(), apiKeyEntity.getUserId(), apiKeyEntity.getId());
        if (!response.isSuccessful()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse(response.getMessage(), response.getDetails()));
        }

        return ResponseEntity.ok(new UserResponse(user));

    }

    private static String normalizeRole(final String role) {
        final String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (!UserService.ROLE_USER.equals(normalized) && !UserService.ROLE_ADMIN.equals(normalized)) {
            throw new BadRequestException("role must be " + UserService.ROLE_USER + " or " + UserService.ROLE_ADMIN + ".", "role");
        }
        return normalized;
    }

}
