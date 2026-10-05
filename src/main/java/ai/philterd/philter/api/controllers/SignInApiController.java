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

import ai.philterd.philter.api.requests.SignInRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.SignInResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.SignInConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Exchanges a username and password for a session key, so a person can use Philter through a user
 * interface without the interface holding a key of its own. Off unless {@code PASSWORD_SIGN_IN_ENABLED}
 * is set, so a deployment without a user interface exposes no unauthenticated login endpoint.
 */
@Tag(name = "Sign-in", description = "Sign in with a username and password to get a session key. Disabled by default.")
@Controller
public class SignInApiController extends AbstractApiController {

    /** The same for every failure, so a caller cannot tell which usernames exist. */
    private static final String FAILED = "Invalid username or password.";

    private static final int AUDITED_USERNAME_LENGTH = 100;

    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;

    public SignInApiController(final ApiKeyDataService apiKeyDataService, final ApiKeyCache apiKeyCache,
                               final UserService userService, final AuditEventPublisher auditEventPublisher) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Operation(
            summary = "Sign in.",
            description = "Takes a username and password and returns a session key for that user, which expires after "
                    + "SESSION_KEY_IDLE_TIMEOUT_MINUTES without a request or SESSION_KEY_MAX_LIFETIME_MINUTES after "
                    + "issue. The key holds every scope, with the user's role still deciding administrator access, but "
                    + "cannot create API keys. A user who must change their password gets a key that can only do that "
                    + "and sign out. A wrong password, an unknown user, a user without a password, and a deactivated user "
                    + "all get the same 401. Requires no API key. Returns 404 unless PASSWORD_SIGN_IN_ENABLED is true. "
                    + "Recorded as sign_in_succeeded or sign_in_failed with the username and client IP address, never "
                    + "the password.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Signed in. The session key is returned here and nowhere else.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SignInResponse.class))),
            @ApiResponse(responseCode = "401", description = "The username or password is not valid.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "Password sign-in is not enabled.", content = @Content)
    })
    @SecurityRequirements
    @RequestMapping(value = "/api/sign-in", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> signIn(
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody SignInRequest request,
            final HttpServletRequest httpRequest) {

        if (!SignInConfig.isPasswordSignInEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String username = request.getUsername() == null ? null : request.getUsername().trim();
        final String clientIp = getClientIpAddress(httpRequest);
        final UserEntity user = userService.authenticate(username, request.getPassword());

        if (user == null) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGN_IN_FAILED, null, null, clientIp,
                    "username: " + auditable(username));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new GenericResponse(FAILED));
        }

        final ApiKeyEntity sessionKey = apiKeyService.createSessionKey(requestId, user.getId(), ApiKeyScope.all(),
                user.isPasswordChangeRequired(), Source.API.getSource(), "signed in");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGN_IN_SUCCEEDED, user.getId(), sessionKey.getId(),
                clientIp, "username: " + user.getUsername() + ", api_key: " + sessionKey.getId());

        return ResponseEntity.ok(new SignInResponse(user.getUsername(), sessionKey));

    }

    /**
     * The username as the audit log records it. It comes from an unauthenticated caller, so it is cut to
     * {@value #AUDITED_USERNAME_LENGTH} characters, and control characters and commas, which could
     * fake further fields in the details, are replaced.
     */
    static String auditable(final String username) {
        if (username == null) {
            return "(none)";
        }
        final String cleaned = username.replaceAll("[\\p{Cntrl},]", "_");
        return cleaned.length() <= AUDITED_USERNAME_LENGTH ? cleaned
                : cleaned.substring(0, AUDITED_USERNAME_LENGTH) + "...";
    }

}
