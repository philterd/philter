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

import ai.philterd.philter.api.requests.SignInMfaRequest;
import ai.philterd.philter.api.requests.SignInRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.SignInChallengeResponse;
import ai.philterd.philter.api.responses.SignInResponse;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.config.SignInConfig;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.SignInChallengeDataService;
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
import org.bson.types.ObjectId;
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

    /** The same whether the challenge or the code was wrong. */
    private static final String FAILED_MFA = "Invalid, used, or expired challenge, or invalid code. Sign in again.";

    private static final int AUDITED_USERNAME_LENGTH = 100;

    private final UserService userService;
    private final AuditEventPublisher auditEventPublisher;
    private final SignInChallengeDataService challenges;
    private final AdminSettingsDataService adminSettingsDataService;

    public SignInApiController(final ApiKeyDataService apiKeyDataService, final ApiKeyCache apiKeyCache,
                               final UserService userService, final AuditEventPublisher auditEventPublisher,
                               final SignInChallengeDataService challenges,
                               final AdminSettingsDataService adminSettingsDataService) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.auditEventPublisher = auditEventPublisher;
        this.challenges = challenges;
        this.adminSettingsDataService = adminSettingsDataService;
    }

    @Operation(
            summary = "Sign in.",
            description = "Takes a username and password. For a user enrolled in MFA, returns a single-use challenge "
                    + "that expires in five minutes; send it with a code to POST /api/sign-in/mfa to get the key. "
                    + "Otherwise returns a session key for the user, which expires after "
                    + "SESSION_KEY_IDLE_TIMEOUT_MINUTES without a request or SESSION_KEY_MAX_LIFETIME_MINUTES after "
                    + "issue. The key holds every scope, with the user's role still deciding administrator access, but "
                    + "cannot create API keys. A user who must change their password, or must enroll in MFA, gets a key "
                    + "that can only do that and sign out. A wrong password, an unknown user, a user without a password, "
                    + "and a deactivated user all get the same 401. Requires no API key. Returns 404 unless "
                    + "PASSWORD_SIGN_IN_ENABLED is true. Recorded as sign_in_succeeded or sign_in_failed with the "
                    + "username and client IP address, never the password.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Signed in, or, for a user enrolled in MFA, a challenge. "
                    + "A session key is returned here and nowhere else.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(oneOf = {SignInResponse.class, SignInChallengeResponse.class}))),
            @ApiResponse(responseCode = "401", description = "The username or password is not valid.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "403", description = "The password is right, but the user's MFA is locked after "
                    + "repeated bad codes. An administrator must unlock it.",
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

        // An enrolled user is always challenged, even if MFA has since been made unavailable: turning a
        // setting off must not quietly let someone in on a password alone.
        if (user.isMfaEnabled()) {
            if (user.isMfaLocked()) {
                return locked(requestId, user, clientIp);
            }
            final SignInChallengeDataService.Challenge challenge = challenges.create(user.getId());
            return ResponseEntity.ok(new SignInChallengeResponse(challenge.token(), challenge.expiresAt()));
        }

        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        final boolean mfaEnrollmentRequired = settings != null && settings.isMfaAvailable() && settings.isMfaRequired();

        return issue(requestId, user, mfaEnrollmentRequired, clientIp, "username: " + user.getUsername());

    }

    @Operation(
            summary = "Complete sign-in with an MFA code.",
            description = "Takes the challenge POST /api/sign-in returned and a code from the user's authenticator app, "
                    + "and returns the session key. The challenge is used up by any attempt, right or wrong, so a wrong "
                    + "code means signing in again. Each code is accepted once. The fifth consecutive bad code locks "
                    + "the user until an administrator unlocks them. Requires no API key. Returns 404 unless "
                    + "PASSWORD_SIGN_IN_ENABLED is true. Recorded as sign_in_succeeded or sign_in_failed.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Signed in. The session key is returned here and nowhere else.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SignInResponse.class))),
            @ApiResponse(responseCode = "401", description = "The challenge is unknown, used, or expired, or the code is not valid.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "403", description = "The user's MFA is locked after repeated bad codes.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "Password sign-in is not enabled.", content = @Content)
    })
    @SecurityRequirements
    @RequestMapping(value = "/api/sign-in/mfa", method = RequestMethod.POST,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> signInMfa(
            final @RequestAttribute("requestId") String requestId,
            final @RequestBody SignInMfaRequest request,
            final HttpServletRequest httpRequest) {

        if (!SignInConfig.isPasswordSignInEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final String clientIp = getClientIpAddress(httpRequest);
        final ObjectId userId = challenges.consume(request.getChallenge());
        final UserEntity user = userId == null ? null : userService.findOneById(userId);

        if (user == null || user.isDeactivated()) {
            auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGN_IN_FAILED, null, null, clientIp,
                    "reason: unknown, used, or expired MFA challenge");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new GenericResponse(FAILED_MFA));
        }

        return switch (userService.checkMfaCode(requestId, user, request.getCode(), Source.API.getSource())) {
            case ACCEPTED -> issue(requestId, user, false, clientIp, "username: " + user.getUsername() + ", mfa: true");
            case LOCKED -> locked(requestId, user, clientIp);
            case REFUSED -> {
                auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGN_IN_FAILED, user.getId(), user.getId(), clientIp,
                        "username: " + user.getUsername() + ", reason: invalid MFA code");
                yield ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new GenericResponse(FAILED_MFA));
            }
        };

    }

    private ResponseEntity<Object> issue(final String requestId, final UserEntity user, final boolean mfaEnrollmentOnly,
                                         final String clientIp, final String auditDetails) {

        final ApiKeyEntity sessionKey = apiKeyService.createSessionKey(requestId, user.getId(), ApiKeyScope.all(),
                user.isPasswordChangeRequired(), mfaEnrollmentOnly, Source.API.getSource(), "signed in");

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGN_IN_SUCCEEDED, user.getId(), sessionKey.getId(),
                clientIp, auditDetails + ", api_key: " + sessionKey.getId());

        return ResponseEntity.ok(new SignInResponse(user.getUsername(), sessionKey));

    }

    private ResponseEntity<Object> locked(final String requestId, final UserEntity user, final String clientIp) {
        auditEventPublisher.auditEvent(requestId, AuditLogEvent.SIGN_IN_FAILED, user.getId(), user.getId(), clientIp,
                "username: " + user.getUsername() + ", reason: MFA locked");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                "MFA is locked after repeated bad codes. An administrator must unlock it."));
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
