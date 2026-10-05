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
import ai.philterd.philter.api.requests.SetWebhookRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.WebhookResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
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
import org.bson.types.ObjectId;
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
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Reads, sets, and removes the calling user's webhook, the destination for asynchronous redaction
 * results. An administrator reaches another user's webhook with {@code owner}, under the usual
 * cross-user rules. Validation and auditing are in {@link UserService}, shared with the dashboard.
 */
@Tag(name = "Webhook",
        description = "Read, set, and remove the user's webhook for asynchronous redaction results.")
@Controller
public class WebhookApiController extends AbstractApiController {

    private final UserService userService;
    private final AdminSettingsDataService adminSettingsDataService;
    private final AuditEventPublisher auditEventPublisher;

    public WebhookApiController(final ApiKeyDataService apiKeyDataService,
                                final ApiKeyCache apiKeyCache,
                                final UserService userService,
                                final AdminSettingsDataService adminSettingsDataService,
                                final AuditEventPublisher auditEventPublisher) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.adminSettingsDataService = adminSettingsDataService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @Operation(
            summary = "Get the webhook.",
            description = "Returns the webhook URL and whether a secret is set; the secret is never returned. "
                    + "Admins may read another user's webhook via the owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The webhook configuration. The url is null when none is set.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebhookResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold webhooks:read.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.WEBHOOKS_READ)
    @RequestMapping(value = "/api/webhook", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getWebhook(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final UserEntity user = targetUser(requestId, caller, owner, "read webhook");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        return ResponseEntity.ok(new WebhookResponse(user));

    }

    @Operation(
            summary = "Set the webhook.",
            description = "Sets the webhook URL and secret. The URL must be http or https and its host must be "
                    + "permitted by the administrator's webhook destination allowlist, which refuses private and "
                    + "loopback addresses when empty. The secret must be at least 16 characters. Admins may set "
                    + "another user's webhook via the owner parameter. Recorded as a webhook_configured audit event "
                    + "naming the calling user and API key; the URL and secret are not recorded.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The webhook was set.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebhookResponse.class))),
            @ApiResponse(responseCode = "400", description = "The URL or secret is missing, the URL is invalid or not permitted, or the secret is too short. The message says which."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold webhooks:write.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.WEBHOOKS_WRITE)
    @RequestMapping(value = "/api/webhook", method = RequestMethod.PUT,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> setWebhook(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestBody SetWebhookRequest request) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final UserEntity user = targetUser(requestId, caller, owner, "set webhook");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        final AdminSettingsEntity settings = adminSettingsDataService.findAdminSettings();
        final ServiceResponse response = userService.setWebhook(requestId, user, request.getUrl(), request.getSecret(),
                settings == null ? null : settings.getWebhookAllowlist(),
                Source.API.getSource(), caller.getUserId(), caller.getId());
        if (!response.isSuccessful()) {
            throw new BadRequestException(response.getMessage());
        }

        return ResponseEntity.ok(new WebhookResponse(user));

    }

    @Operation(
            summary = "Remove the webhook.",
            description = "Removes the webhook URL and secret, so asynchronous results are no longer delivered. "
                    + "Admins may remove another user's webhook via the owner parameter. Recorded as a "
                    + "webhook_removed audit event naming the calling user and API key.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "The webhook was removed, or none was set.", content = @Content),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold webhooks:write.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.WEBHOOKS_WRITE)
    @RequestMapping(value = "/api/webhook", method = RequestMethod.DELETE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> removeWebhook(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final UserEntity user = targetUser(requestId, caller, owner, "remove webhook");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        userService.removeWebhook(requestId, user, Source.API.getSource(), caller.getUserId(), caller.getId());

        return ResponseEntity.noContent().build();

    }

    /**
     * The user whose webhook the request targets, resolved by the usual {@code owner} rules, or
     * {@code null} when the owner does not exist or the caller may not reach it.
     */
    private UserEntity targetUser(final String requestId, final ApiKeyEntity caller, final String owner,
                                  final String action) {

        final ObjectId userId = resolveTargetUserId(userService, caller.getUserId(), owner);
        if (userId == null) {
            return null;
        }

        auditAdminCrossUserAccess(auditEventPublisher, requestId, caller.getUserId(), userId, action);

        return userService.findOneById(userId);

    }

}
