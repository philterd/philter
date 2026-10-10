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

import ai.philterd.philter.api.responses.LimitsResponse;
import ai.philterd.philter.api.security.AnyApiKey;
import ai.philterd.philter.audit.AuditLogService;
import ai.philterd.philter.config.SessionKeyConfig;
import ai.philterd.philter.data.entities.ApiKeyEntity;
import ai.philterd.philter.data.entities.LegalHoldEntity;
import ai.philterd.philter.data.entities.UserEntity;
import ai.philterd.philter.data.services.ApiKeyDataService;
import ai.philterd.philter.data.services.ContextDataService;
import ai.philterd.philter.data.services.ContextEntryDataService;
import ai.philterd.philter.data.services.CustomListDataService;
import ai.philterd.philter.data.services.PolicyDataService;
import ai.philterd.philter.data.services.RedactListsDataService;
import ai.philterd.philter.data.services.UserService;
import ai.philterd.philter.model.Constants;
import ai.philterd.philter.services.cache.ApiKeyCache;
import ai.philterd.philter.services.webhook.WebhookSettings;
import ai.philterd.philter.utils.PathSafeNames;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/**
 * Reports the limits and rules Philter enforces, and what the caller may do, so a client such as a
 * user interface reads them rather than copying them.
 */
@Tag(name = "Limits", description = "The limits and rules Philter enforces, and what the caller may do.")
@Controller
public class LimitsApiController extends AbstractApiController {

    private final UserService userService;
    private final ContextDataService contextDataService;

    public LimitsApiController(final ApiKeyDataService apiKeyDataService, final ApiKeyCache apiKeyCache,
                               final UserService userService, final ContextDataService contextDataService) {
        super(apiKeyDataService, apiKeyCache);
        this.userService = userService;
        this.contextDataService = contextDataService;
    }

    @Operation(
            summary = "Get Philter's limits and the caller's capabilities.",
            description = "Returns the sizes, lengths, and name rules Philter enforces, the session key timeouts, and "
                    + "what the caller may do: their role, how many contexts they have, and whether they may reach "
                    + "other users' resources or delete ledger chains. Each value is the one Philter enforces, "
                    + "including any set through its environment variable. Any key may call it, whatever its scopes, "
                    + "including a session key that must first change its password or enroll in MFA, since it "
                    + "describes the API and the caller's own access. For an administrator, crossUserAccess and "
                    + "ledgerDeletion reflect ADMIN_CROSS_USER_ACCESS_ENABLED and LEDGER_DELETION_ENABLED; for anyone "
                    + "else they are false, so the response does not reveal those settings.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The limits and the caller's capabilities.",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = LimitsResponse.class))),
            @ApiResponse(responseCode = "401", description = "The Authorization header is absent or the API key is not recognized.")
    })
    @AnyApiKey
    @RequestMapping(value = "/api/limits", method = RequestMethod.GET, produces = MediaType.APPLICATION_JSON_VALUE)
    public @ResponseBody ResponseEntity<LimitsResponse> getLimits(
            final @RequestHeader(HttpHeaders.AUTHORIZATION) String authorizationHeader) {

        final ApiKeyEntity apiKeyEntity = requireApiKey(authorizationHeader);

        final UserEntity user = userService.findOneById(apiKeyEntity.getUserId());
        final String role = user == null ? null : user.getRole();
        final boolean admin = UserService.ROLE_ADMIN.equalsIgnoreCase(role);

        final LimitsResponse.Caller caller = new LimitsResponse.Caller(
                role,
                contextDataService.count(apiKeyEntity.getUserId()),
                admin && isCrossUserAccessEnabled(),
                admin && isLedgerDeletionEnabled());

        return ResponseEntity.ok(new LimitsResponse(
                new LimitsResponse.Requests(Constants.MAX_FILE_SIZE_BYTES, Constants.MAX_FILE_SIZE_BYTES_OTHER,
                        DEFAULT_LIMIT, MAX_LIMIT),
                passwordRules(),
                new LimitsResponse.Names(PathSafeNames.FORBIDDEN_CHARACTERS, true, PathSafeNames.RESERVED_NAMES,
                        PathSafeNames.RULE),
                new LimitsResponse.Policies(PolicyDataService.POLICY_NAME_MAX_LENGTH, PolicyDataService.POLICY_NAME_REGEX,
                        PolicyDataService.MANAGED_NAME_PREFIX, PolicyDataService.DEFAULT_POLICY_NAME,
                        PolicyDataService.POLICY_DESCRIPTION_MAX_LENGTH, PolicyDataService.POLICY_NOTES_MAX_LENGTH),
                new LimitsResponse.CustomLists(CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS,
                        CustomListDataService.MAXIMUM_ITEM_LENGTH),
                new LimitsResponse.RedactLists(RedactListsDataService.MAXIMUM_TERMS_PER_LIST,
                        RedactListsDataService.MAXIMUM_TERM_LENGTH),
                new LimitsResponse.Contexts(ContextDataService.MAXIMUM_CONTEXTS_PER_USER,
                        ContextEntryDataService.MAX_CONTEXT_SIZE),
                new LimitsResponse.Webhook(WebhookSettings.MIN_SECRET_LENGTH),
                new LimitsResponse.LegalHolds(List.of(LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, LegalHoldEntity.SCOPE_USER)),
                new LimitsResponse.Users(List.of(UserService.ROLE_ADMIN, UserService.ROLE_USER),
                        List.of(UsersApiController.SELF)),
                new LimitsResponse.AuditExport(AuditLogService.MAX_EXPORT_WINDOW_DAYS,
                        AuditApiController.EXPORT_DEFAULT_LIMIT, AuditApiController.EXPORT_MAX_LIMIT),
                new LimitsResponse.SessionKeys(SessionKeyConfig.idleTimeoutMinutes(), SessionKeyConfig.maxLifetimeMinutes()),
                caller));

    }

    /** The password rules, shared with the sign-in options so the two cannot differ. */
    static LimitsResponse.Password passwordRules() {
        return new LimitsResponse.Password(UserService.MIN_PASSWORD_CHARACTERS, UserService.MAX_PASSWORD_BYTES);
    }

}
