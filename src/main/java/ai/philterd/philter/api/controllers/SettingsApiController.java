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
import ai.philterd.philter.api.requests.UpdateSettingsRequest;
import ai.philterd.philter.api.responses.GenericResponse;
import ai.philterd.philter.api.responses.SettingsResponse;
import ai.philterd.philter.api.security.RequiresScope;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.services.AdminSettingsDataService;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.ApiKeyScope;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Reads and changes the deployment's admin settings: differential-privacy counts, output signing, the
 * webhook destination allowlist, Phield publishing, and MFA. Requires an administrator as well as the scope.
 */
@Tag(name = "Settings", description = "Read and change the deployment's admin settings. Requires an administrator.")
@Controller
public class SettingsApiController extends AbstractApiController {

    private final AdminSettingsDataService adminSettingsDataService;
    private final UserService userService;

    public SettingsApiController(final ApiKeyDataService apiKeyDataService,
                                 final ApiKeyCache apiKeyCache,
                                 final AdminSettingsDataService adminSettingsDataService,
                                 final UserService userService) {
        super(apiKeyDataService, apiKeyCache);
        this.adminSettingsDataService = adminSettingsDataService;
        this.userService = userService;
    }

    @Operation(summary = "Get the admin settings.",
            description = "Returns the deployment's admin settings. The Phield API key is never returned, only "
                    + "whether one is set. Requires an administrator as well as the scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The settings.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SettingsResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold settings:read, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.SETTINGS_READ)
    @RequestMapping(value = "/api/settings", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> getSettings(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, caller, "Reading the admin settings");
        if (refusal != null) {
            return refusal;
        }

        return ResponseEntity.ok(new SettingsResponse(adminSettingsDataService.findAdminSettings(), List.of()));

    }

    @Operation(summary = "Change admin settings.",
            description = "Changes the settings given in the body and leaves the rest as they are. An empty "
                    + "phieldApiKey removes the key. phieldUrl must be an absolute http or https URL, and is required "
                    + "while Phield is enabled; both are checked when a request changes Phield. Each webhookAllowlist entry must be a hostname, an IP address, or a CIDR "
                    + "range. Nothing is changed if any value is invalid. Returns the settings as saved, with warnings, "
                    + "such as a Phield API key that will be sent over http. Recorded as a settings_updated audit event "
                    + "naming the settings that changed and the calling API key, not their values. Requires an "
                    + "administrator as well as the scope.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The settings as saved.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SettingsResponse.class))),
            @ApiResponse(responseCode = "400", description = "A value is invalid. The body says which, and nothing was changed."),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized."),
            @ApiResponse(responseCode = "403", description = "The key does not hold settings:write, or the caller is not an administrator.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenericResponse.class)))
    })
    @RequiresScope(ApiKeyScope.SETTINGS_WRITE)
    @RequestMapping(value = "/api/settings", method = RequestMethod.PATCH,
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<Object> updateSettings(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader,
            final @RequestBody UpdateSettingsRequest request) {

        final ApiKeyEntity caller = requireApiKey(authorizationHeader);

        final ResponseEntity<Object> refusal = refuseNonAdmin(userService, caller, "Changing the admin settings");
        if (refusal != null) {
            return refusal;
        }

        final List<String> warnings;
        try {
            warnings = adminSettingsDataService.update(new AdminSettingsDataService.Update(
                    request.getDiffuseCountsEnabled(), request.getSigningEnabled(), request.getWebhookAllowlist(),
                    request.getPhieldEnabled(), request.getPhieldUrl(), request.getPhieldSourceId(),
                    request.getPhieldOrganization(), request.getPhieldApiKey(), request.getMfaAvailable(),
                    request.getMfaRequired()), caller.getUserId(), caller.getId());
        } catch (final IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        } catch (final AccessDeniedException ex) {
            // The caller stopped being an active administrator after the check above.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new GenericResponse(
                    "Changing the admin settings requires an administrator."));
        }

        return ResponseEntity.ok(new SettingsResponse(adminSettingsDataService.findAdminSettings(), warnings));

    }

}
