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

import ai.philterd.philter.api.exceptions.RestApiExceptions;
import ai.philterd.philter.api.security.ApiKeyScopeInterceptor;
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
import ai.philterd.philter.services.encryption.EncryptionService;
import ai.philterd.philter.services.webhook.WebhookSettings;
import ai.philterd.philter.utils.PathSafeNames;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.hamcrest.Matchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limits must be the values Philter enforces, read from the same constants, and the caller's
 * capabilities must not reveal the deployment's settings to someone who is not an administrator.
 */
@ExtendWith(MockitoExtension.class)
class LimitsApiControllerTest {

    private static final String API_KEY = "sk_abcdefghijklmnopqrstuvwxyz012345";
    private static final String API_KEY_HASH = EncryptionService.hashSha256(API_KEY);
    private static final String AUTH_HEADER = "Bearer " + API_KEY;

    @Mock private ApiKeyDataService apiKeyDataService;
    @Mock private ApiKeyCache apiKeyCache;
    @Mock private UserService userService;
    @Mock private ContextDataService contextDataService;

    private ObjectId callerUserId;
    private ApiKeyEntity callerKey;

    @BeforeEach
    void setUp() {
        callerUserId = new ObjectId();
        callerKey = keyHolding(Set.of("redact"));
        lenient().when(apiKeyCache.containsApiKey(API_KEY_HASH)).thenReturn(true);
        lenient().when(apiKeyCache.get(API_KEY_HASH)).thenAnswer(invocation -> callerKey);
    }

    private ApiKeyEntity keyHolding(final Set<String> scopes) {
        final ApiKeyEntity key = new ApiKeyEntity();
        key.setId(new ObjectId());
        key.setUserId(callerUserId);
        key.setApiKeyHash(API_KEY_HASH);
        key.setScopes(new LinkedHashSet<>(scopes));
        return key;
    }

    private void callerHasRole(final String role) {
        final UserEntity user = new UserEntity();
        user.setId(callerUserId);
        user.setRole(role);
        when(userService.findOneById(callerUserId)).thenReturn(user);
    }

    /** The limits, as a deployment with both administrator switches set as given would report them. */
    private ResultActions getLimits(final boolean crossUserAccess, final boolean ledgerDeletion) throws Exception {

        final LimitsApiController controller =
                new LimitsApiController(apiKeyDataService, apiKeyCache, userService, contextDataService) {
                    @Override
                    protected boolean isCrossUserAccessEnabled() {
                        return crossUserAccess;
                    }

                    @Override
                    protected boolean isLedgerDeletionEnabled() {
                        return ledgerDeletion;
                    }
                };

        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ApiKeyScopeInterceptor())
                .setControllerAdvice(new RestApiExceptions())
                .build();

        return mockMvc.perform(get("/api/limits")
                .header("Authorization", AUTH_HEADER)
                .requestAttr(AbstractApiController.API_KEY_ENTITY_ATTRIBUTE, callerKey));

    }

    @Test
    @DisplayName("Reports the limits Philter enforces, to a key with only the redact scope")
    void reportsTheEnforcedLimits() throws Exception {

        callerHasRole("user");
        when(contextDataService.count(callerUserId)).thenReturn(3);

        getLimits(false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests.maxDocumentBytes").value(Constants.MAX_FILE_SIZE_BYTES))
                .andExpect(jsonPath("$.requests.maxBodyBytes").value(Constants.MAX_FILE_SIZE_BYTES_OTHER))
                .andExpect(jsonPath("$.requests.defaultPageSize").value(AbstractApiController.DEFAULT_LIMIT))
                .andExpect(jsonPath("$.requests.maxPageSize").value(AbstractApiController.MAX_LIMIT))
                .andExpect(jsonPath("$.password.minCharacters").value(UserService.MIN_PASSWORD_CHARACTERS))
                .andExpect(jsonPath("$.password.maxBytes").value(UserService.MAX_PASSWORD_BYTES))
                .andExpect(jsonPath("$.names.forbiddenCharacters").value(contains(
                        PathSafeNames.FORBIDDEN_CHARACTERS.toArray())))
                .andExpect(jsonPath("$.names.controlCharactersForbidden").value(true))
                .andExpect(jsonPath("$.names.reservedNames").value(contains(".", "..")))
                .andExpect(jsonPath("$.names.rule").value(PathSafeNames.RULE))
                .andExpect(jsonPath("$.policies.nameMaxLength").value(PolicyDataService.POLICY_NAME_MAX_LENGTH))
                .andExpect(jsonPath("$.policies.namePattern").value(PolicyDataService.POLICY_NAME_REGEX))
                .andExpect(jsonPath("$.policies.reservedNamePrefix").value("managed_"))
                .andExpect(jsonPath("$.policies.defaultPolicyName").value("default"))
                .andExpect(jsonPath("$.policies.descriptionMaxLength").value(PolicyDataService.POLICY_DESCRIPTION_MAX_LENGTH))
                .andExpect(jsonPath("$.policies.notesMaxLength").value(PolicyDataService.POLICY_NOTES_MAX_LENGTH))
                .andExpect(jsonPath("$.customLists.maxItems").value(CustomListDataService.MAXIMUM_NUMBER_OF_ITEMS))
                .andExpect(jsonPath("$.customLists.itemMaxLength").value(CustomListDataService.MAXIMUM_ITEM_LENGTH))
                .andExpect(jsonPath("$.redactLists.maxTerms").value(RedactListsDataService.MAXIMUM_TERMS_PER_LIST))
                .andExpect(jsonPath("$.redactLists.termMaxLength").value(RedactListsDataService.MAXIMUM_TERM_LENGTH))
                .andExpect(jsonPath("$.contexts.maxPerUser").value(ContextDataService.MAXIMUM_CONTEXTS_PER_USER))
                .andExpect(jsonPath("$.contexts.maxEntries").value(ContextEntryDataService.MAX_CONTEXT_SIZE))
                .andExpect(jsonPath("$.webhook.secretMinLength").value(WebhookSettings.MIN_SECRET_LENGTH))
                .andExpect(jsonPath("$.legalHolds.scopeTypes").value(contains(
                        LegalHoldEntity.SCOPE_DOCUMENT_CHAIN, LegalHoldEntity.SCOPE_USER)))
                .andExpect(jsonPath("$.users.roles").value(contains(UserService.ROLE_ADMIN, UserService.ROLE_USER)))
                .andExpect(jsonPath("$.users.reservedUsernames").value(contains("me")))
                .andExpect(jsonPath("$.auditExport.maxWindowDays").value(AuditLogService.MAX_EXPORT_WINDOW_DAYS))
                .andExpect(jsonPath("$.auditExport.defaultPageSize").value(AuditApiController.EXPORT_DEFAULT_LIMIT))
                .andExpect(jsonPath("$.auditExport.maxPageSize").value(AuditApiController.EXPORT_MAX_LIMIT))
                .andExpect(jsonPath("$.sessionKeys.idleTimeoutMinutes").value(SessionKeyConfig.idleTimeoutMinutes()))
                .andExpect(jsonPath("$.sessionKeys.maxLifetimeMinutes").value(SessionKeyConfig.maxLifetimeMinutes()))
                .andExpect(jsonPath("$.caller.role").value("user"))
                .andExpect(jsonPath("$.caller.contextCount").value(3));

    }

    @Test
    @DisplayName("An administrator's capabilities follow the deployment's switches")
    void administratorCapabilitiesFollowTheSwitches() throws Exception {

        callerHasRole("admin");

        getLimits(true, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller.role").value("admin"))
                .andExpect(jsonPath("$.caller.crossUserAccess").value(true))
                .andExpect(jsonPath("$.caller.ledgerDeletion").value(true));

    }

    @Test
    @DisplayName("An administrator with the switches off has neither capability")
    void administratorWithTheSwitchesOff() throws Exception {

        callerHasRole("admin");

        getLimits(false, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller.crossUserAccess").value(false))
                .andExpect(jsonPath("$.caller.ledgerDeletion").value(false));

    }

    @Test
    @DisplayName("A user who is not an administrator does not learn how the switches are set")
    void nonAdministratorDoesNotLearnTheSwitches() throws Exception {

        callerHasRole("user");

        getLimits(true, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caller.crossUserAccess").value(false))
                .andExpect(jsonPath("$.caller.ledgerDeletion").value(false));

    }

}
