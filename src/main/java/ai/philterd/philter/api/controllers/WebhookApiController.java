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
import ai.philterd.philter.api.responses.WebhookDeliveriesResponse;
import ai.philterd.philter.api.responses.WebhookDeliveryView;
import ai.philterd.philter.api.responses.WebhookResponse;
import ai.philterd.philter.api.responses.WebhookTestResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.audit.AuditEventPublisher;
import ai.philterd.philter.data.entities.AdminSettingsEntity;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.entities.WebhookDeliveryEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.data.services.WebhookDeliveryDataService;
import ai.philterd.philter.model.ApiKeyScope;
import ai.philterd.philter.model.AuditLogEvent;
import ai.philterd.philter.model.ServiceResponse;
import ai.philterd.philter.model.Source;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.webhook.WebhookService;
import com.google.gson.Gson;
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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads, sets, removes, and tests the calling user's webhook, the destination for asynchronous
 * redaction results, and lists its deliveries. An administrator reaches another user's webhook with {@code owner}, under the usual
 * cross-user rules. Validation and auditing are in {@link UserService}.
 */
@Tag(name = "Webhook",
        description = "Read, set, and remove the user's webhook for asynchronous redaction results.")
@Controller
public class WebhookApiController extends AbstractApiController {

    private final UserService userService;
    private final AdminSettingsDataService adminSettingsDataService;
    private static final Gson GSON = new Gson();

    private final AuditEventPublisher auditEventPublisher;
    private final WebhookService webhookService;
    private final WebhookDeliveryDataService webhookDeliveryDataService;

    public WebhookApiController(final ApiKeyDataService apiKeyDataService,
                                final ApiKeyCache apiKeyCache,
                                final UserService userService,
                                final AdminSettingsDataService adminSettingsDataService,
                                final AuditEventPublisher auditEventPublisher,
                                final WebhookService webhookService,
                                final WebhookDeliveryDataService webhookDeliveryDataService) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.adminSettingsDataService = adminSettingsDataService;
        this.auditEventPublisher = auditEventPublisher;
        this.webhookService = webhookService;
        this.webhookDeliveryDataService = webhookDeliveryDataService;
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
                    + "loopback addresses when empty. The secret must be at least 16 characters. Leave the secret out to "
                    + "keep the one already set, so the URL can change without rotating it. Admins may set "
                    + "another user's webhook via the owner parameter. Recorded as a webhook_configured audit event "
                    + "naming the calling user and API key; the URL and secret are not recorded.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The webhook was set.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebhookResponse.class))),
            @ApiResponse(responseCode = "400", description = "The URL is missing, the secret is missing and none is set yet, the URL is invalid or not permitted, or the secret is too short. The message says which."),
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

    @Operation(
            summary = "Send a test event to the webhook.",
            description = "Sends one signed WEBHOOK_TEST event to the webhook now, signed and checked against the "
                    + "destination allowlist as a real delivery is, and returns what happened: whether the receiver "
                    + "answered 2xx, its status code, and any error. The event is not queued or retried and does not "
                    + "appear in GET /api/webhook/deliveries. Its body has \"test\": true so a receiver can ignore it. "
                    + "Waits for the receiver up to WEBHOOK_CONNECT_TIMEOUT_SECONDS and WEBHOOK_RESPONSE_TIMEOUT_SECONDS. "
                    + "Admins may test another user's webhook via the owner parameter. Recorded as a webhook_tested "
                    + "audit event with the outcome; the URL and secret are not recorded.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The test was attempted. delivered says whether the receiver accepted it.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebhookTestResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold webhooks:write.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it.", content = @Content),
            @ApiResponse(responseCode = "409", description = "No webhook is set, so there is nothing to test.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.WEBHOOKS_WRITE)
    @RequestMapping(value = "/api/webhook/test", method = RequestMethod.POST, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> testWebhook(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestParam(value = "owner", required = false) String owner) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final UserEntity user = targetUser(requestId, caller, owner, "test webhook");
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        if (user.getWebhookUrl() == null || user.getWebhookUrl().isBlank()
                || user.getWebhookSecret() == null || user.getWebhookSecret().isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(new GenericResponse("No webhook is set, so there is nothing to test."));
        }

        final String deliveryId = new ObjectId().toHexString();
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", WebhookDeliveryEntity.EVENT_WEBHOOK_TEST);
        payload.put("test", true);
        payload.put("timestamp", Instant.now().toString());

        final WebhookService.TestResult result =
                webhookService.test(user.getWebhookUrl(), deliveryId, GSON.toJson(payload), user.getWebhookSecret());

        auditEventPublisher.auditEvent(requestId, AuditLogEvent.WEBHOOK_TESTED, caller.getUserId(), user.getId(),
                Source.API.getSource(), "delivered: " + result.delivered()
                        + (result.statusCode() == null ? "" : ", status: " + result.statusCode())
                        + ", api_key: " + caller.getId());

        return ResponseEntity.ok(new WebhookTestResponse(result.delivered(), result.statusCode(), result.error(),
                result.durationMillis(), deliveryId));

    }

    @Operation(
            summary = "List the webhook's deliveries.",
            description = "Lists the user's webhook deliveries, newest first, with each one's status, attempts, last "
                    + "error, and when it was queued, last changed, is next due, and was delivered, so a failing webhook "
                    + "can be diagnosed. The secret and the event body are not included. A delivery that ended, "
                    + "delivered or failed, is kept for WEBHOOK_DELIVERIES_TTL_SECONDS (30 days by default). Paged with "
                    + "offset and limit (25 by default, at most 100). Admins may list another user's deliveries via the "
                    + "owner parameter.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "A page of deliveries and the total.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = WebhookDeliveriesResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold webhooks:read.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class))),
            @ApiResponse(responseCode = "404", description = "The owner does not exist, or the caller may not reach it.", content = @Content)
    })
    @RequiresScope(ApiKeyScope.WEBHOOKS_READ)
    @RequestMapping(value = "/api/webhook/deliveries", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getDeliveries(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestAttribute("requestId") String requestId,
            final @RequestParam(value = "owner", required = false) String owner,
            final @RequestParam(value = "order", required = false) String order,
            final @RequestParam(value = "offset", defaultValue = "0") int offset,
            final @RequestParam(value = "limit", defaultValue = "25") int limit) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ObjectId userId = resolveTargetUserId(userService, caller.getUserId(), owner);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        auditAdminCrossUserAccess(auditEventPublisher, requestId, caller.getUserId(), userId, "list webhook deliveries");

        final List<WebhookDeliveryView> deliveries = webhookDeliveryDataService
                .findByUserId(userId, normalizeOffset(offset), normalizeLimit(limit),
                        listingSort(null, order, DELIVERY_SORT, "created", true).descending()).stream()
                .map(WebhookDeliveryView::new).toList();

        return ResponseEntity.ok(new WebhookDeliveriesResponse(deliveries, webhookDeliveryDataService.countByUserId(userId)));

    }

    /** The order a listing of deliveries can take. */
    private static final Map<String, String> DELIVERY_SORT = sortFields("created", "created_at");

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
