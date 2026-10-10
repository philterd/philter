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
package ai.philterd.philter.api.responses;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * The limits and rules Philter enforces, and what the caller may do, so a client can check input and
 * show or hide features without copying Philter's rules. Every value is read from the constant or
 * setting Philter enforces, so the two cannot drift.
 */
public class LimitsResponse {

    private final Requests requests;
    private final Password password;
    private final Names names;
    private final Policies policies;
    private final CustomLists customLists;
    private final RedactLists redactLists;
    private final Contexts contexts;
    private final Webhook webhook;
    private final LegalHolds legalHolds;
    private final Users users;
    private final AuditExport auditExport;
    private final SessionKeys sessionKeys;
    private final Caller caller;

    public LimitsResponse(final Requests requests, final Password password, final Names names,
                          final Policies policies, final CustomLists customLists, final RedactLists redactLists,
                          final Contexts contexts, final Webhook webhook, final LegalHolds legalHolds,
                          final Users users, final AuditExport auditExport, final SessionKeys sessionKeys,
                          final Caller caller) {
        this.requests = requests;
        this.password = password;
        this.names = names;
        this.policies = policies;
        this.customLists = customLists;
        this.redactLists = redactLists;
        this.contexts = contexts;
        this.webhook = webhook;
        this.legalHolds = legalHolds;
        this.users = users;
        this.auditExport = auditExport;
        this.sessionKeys = sessionKeys;
        this.caller = caller;
    }

    @Schema(description = "Request sizes and paging.")
    public Requests getRequests() { return requests; }

    @Schema(description = "The rules a password must meet.")
    public Password getPassword() { return password; }

    @Schema(description = "The rule for names used in a request path: custom list names, context names, legal hold "
            + "references, and usernames.")
    public Names getNames() { return names; }

    @Schema(description = "Policy names, descriptions, and notes.")
    public Policies getPolicies() { return policies; }

    @Schema(description = "Custom list sizes.")
    public CustomLists getCustomLists() { return customLists; }

    @Schema(description = "Always-redact and never-redact list sizes.")
    public RedactLists getRedactLists() { return redactLists; }

    @Schema(description = "Context limits.")
    public Contexts getContexts() { return contexts; }

    @Schema(description = "Webhook rules.")
    public Webhook getWebhook() { return webhook; }

    @Schema(description = "Legal hold rules.")
    public LegalHolds getLegalHolds() { return legalHolds; }

    @Schema(description = "User rules.")
    public Users getUsers() { return users; }

    @Schema(description = "Audit log export limits.")
    public AuditExport getAuditExport() { return auditExport; }

    @Schema(description = "How long a session key, the key issued by password sign-in, stays valid.")
    public SessionKeys getSessionKeys() { return sessionKeys; }

    @Schema(description = "What the caller may do.")
    public Caller getCaller() { return caller; }

    @Schema(name = "RequestLimits")
    public static class Requests {

        private final long maxDocumentBytes;
        private final long maxBodyBytes;
        private final int defaultPageSize;
        private final int maxPageSize;

        public Requests(final long maxDocumentBytes, final long maxBodyBytes, final int defaultPageSize,
                        final int maxPageSize) {
            this.maxDocumentBytes = maxDocumentBytes;
            this.maxBodyBytes = maxBodyBytes;
            this.defaultPageSize = defaultPageSize;
            this.maxPageSize = maxPageSize;
        }

        @Schema(description = "The largest body POST /api/filter and POST /api/explain accept, in bytes. Set by "
                + "MAX_FILE_SIZE_BYTES.")
        public long getMaxDocumentBytes() { return maxDocumentBytes; }

        @Schema(description = "The largest body any other POST or PUT accepts, in bytes. Set by MAX_FILE_SIZE_BYTES_OTHER.")
        public long getMaxBodyBytes() { return maxBodyBytes; }

        @Schema(description = "The page size a listing uses when limit is not given.")
        public int getDefaultPageSize() { return defaultPageSize; }

        @Schema(description = "The largest limit a listing honors. A larger limit is reduced to this.")
        public int getMaxPageSize() { return maxPageSize; }

    }

    @Schema(name = "PasswordRules")
    public static class Password {

        private final int minCharacters;
        private final int maxBytes;

        public Password(final int minCharacters, final int maxBytes) {
            this.minCharacters = minCharacters;
            this.maxBytes = maxBytes;
        }

        @Schema(description = "The fewest characters a password can have.")
        public int getMinCharacters() { return minCharacters; }

        @Schema(description = "The most bytes a password can have in UTF-8.")
        public int getMaxBytes() { return maxBytes; }

    }

    @Schema(name = "PathSafeNameRules")
    public static class Names {

        private final List<String> forbiddenCharacters;
        private final boolean controlCharactersForbidden;
        private final List<String> reservedNames;
        private final String rule;

        public Names(final List<String> forbiddenCharacters, final boolean controlCharactersForbidden,
                     final List<String> reservedNames, final String rule) {
            this.forbiddenCharacters = forbiddenCharacters;
            this.controlCharactersForbidden = controlCharactersForbidden;
            this.reservedNames = reservedNames;
            this.rule = rule;
        }

        @Schema(description = "The characters such a name cannot contain.")
        public List<String> getForbiddenCharacters() { return forbiddenCharacters; }

        @Schema(description = "Whether control characters are refused too.")
        public boolean isControlCharactersForbidden() { return controlCharactersForbidden; }

        @Schema(description = "Whole names that are refused.")
        public List<String> getReservedNames() { return reservedNames; }

        @Schema(description = "The rule in words, as Philter's error messages state it.")
        public String getRule() { return rule; }

    }

    @Schema(name = "PolicyRules")
    public static class Policies {

        private final int nameMaxLength;
        private final String namePattern;
        private final String reservedNamePrefix;
        private final String defaultPolicyName;
        private final int descriptionMaxLength;
        private final int notesMaxLength;

        public Policies(final int nameMaxLength, final String namePattern, final String reservedNamePrefix,
                        final String defaultPolicyName, final int descriptionMaxLength, final int notesMaxLength) {
            this.nameMaxLength = nameMaxLength;
            this.namePattern = namePattern;
            this.reservedNamePrefix = reservedNamePrefix;
            this.defaultPolicyName = defaultPolicyName;
            this.descriptionMaxLength = descriptionMaxLength;
            this.notesMaxLength = notesMaxLength;
        }

        @Schema(description = "The most characters a policy name can have.")
        public int getNameMaxLength() { return nameMaxLength; }

        @Schema(description = "The regular expression a policy name must match.")
        public String getNamePattern() { return namePattern; }

        @Schema(description = "The prefix of managed policies' names, which a user's policy cannot start with.")
        public String getReservedNamePrefix() { return reservedNamePrefix; }

        @Schema(description = "The policy every user is given, which cannot be deleted.")
        public String getDefaultPolicyName() { return defaultPolicyName; }

        @Schema(description = "The most characters a policy description can have.")
        public int getDescriptionMaxLength() { return descriptionMaxLength; }

        @Schema(description = "The most characters a policy's notes can have.")
        public int getNotesMaxLength() { return notesMaxLength; }

    }

    @Schema(name = "CustomListRules")
    public static class CustomLists {

        private final int maxItems;
        private final int itemMaxLength;

        public CustomLists(final int maxItems, final int itemMaxLength) {
            this.maxItems = maxItems;
            this.itemMaxLength = itemMaxLength;
        }

        @Schema(description = "The most items a custom list can have.")
        public int getMaxItems() { return maxItems; }

        @Schema(description = "The most characters an item can have.")
        public int getItemMaxLength() { return itemMaxLength; }

    }

    @Schema(name = "RedactListRules")
    public static class RedactLists {

        private final int maxTerms;
        private final int termMaxLength;

        public RedactLists(final int maxTerms, final int termMaxLength) {
            this.maxTerms = maxTerms;
            this.termMaxLength = termMaxLength;
        }

        @Schema(description = "The most terms each of the always-redact and never-redact lists can have.")
        public int getMaxTerms() { return maxTerms; }

        @Schema(description = "The most characters a term can have.")
        public int getTermMaxLength() { return termMaxLength; }

    }

    @Schema(name = "ContextRules")
    public static class Contexts {

        private final int maxPerUser;
        private final int maxEntries;

        public Contexts(final int maxPerUser, final int maxEntries) {
            this.maxPerUser = maxPerUser;
            this.maxEntries = maxEntries;
        }

        @Schema(description = "The most contexts a user can have.")
        public int getMaxPerUser() { return maxPerUser; }

        @Schema(description = "The most entries a context holds. Adding one more evicts the least-read entry. "
                + "Set by MAX_CONTEXT_SIZE.")
        public int getMaxEntries() { return maxEntries; }

    }

    @Schema(name = "WebhookRules")
    public static class Webhook {

        private final int secretMinLength;

        public Webhook(final int secretMinLength) {
            this.secretMinLength = secretMinLength;
        }

        @Schema(description = "The fewest characters a webhook secret can have.")
        public int getSecretMinLength() { return secretMinLength; }

    }

    @Schema(name = "LegalHoldRules")
    public static class LegalHolds {

        private final List<String> scopeTypes;

        public LegalHolds(final List<String> scopeTypes) {
            this.scopeTypes = scopeTypes;
        }

        @Schema(description = "The scope types a legal hold can have.")
        public List<String> getScopeTypes() { return scopeTypes; }

    }

    @Schema(name = "UserRules")
    public static class Users {

        private final List<String> roles;
        private final List<String> reservedUsernames;

        public Users(final List<String> roles, final List<String> reservedUsernames) {
            this.roles = roles;
            this.reservedUsernames = reservedUsernames;
        }

        @Schema(description = "The roles a user can have.")
        public List<String> getRoles() { return roles; }

        @Schema(description = "Usernames that cannot be used, compared without regard to case.")
        public List<String> getReservedUsernames() { return reservedUsernames; }

    }

    @Schema(name = "AuditExportLimits")
    public static class AuditExport {

        private final int maxWindowDays;
        private final int defaultPageSize;
        private final int maxPageSize;

        public AuditExport(final int maxWindowDays, final int defaultPageSize, final int maxPageSize) {
            this.maxWindowDays = maxWindowDays;
            this.defaultPageSize = defaultPageSize;
            this.maxPageSize = maxPageSize;
        }

        @Schema(description = "The most days between an export's from and to dates.")
        public int getMaxWindowDays() { return maxWindowDays; }

        @Schema(description = "The rows a page of the export has when limit is not given.")
        public int getDefaultPageSize() { return defaultPageSize; }

        @Schema(description = "The most rows a page of the export can have.")
        public int getMaxPageSize() { return maxPageSize; }

    }

    @Schema(name = "SessionKeyLimits")
    public static class SessionKeys {

        private final int idleTimeoutMinutes;
        private final int maxLifetimeMinutes;

        public SessionKeys(final int idleTimeoutMinutes, final int maxLifetimeMinutes) {
            this.idleTimeoutMinutes = idleTimeoutMinutes;
            this.maxLifetimeMinutes = maxLifetimeMinutes;
        }

        @Schema(description = "Minutes without a request after which a session key expires. Set by "
                + "SESSION_KEY_IDLE_TIMEOUT_MINUTES.")
        public int getIdleTimeoutMinutes() { return idleTimeoutMinutes; }

        @Schema(description = "Minutes after issue at which a session key expires, whatever its use. Set by "
                + "SESSION_KEY_MAX_LIFETIME_MINUTES.")
        public int getMaxLifetimeMinutes() { return maxLifetimeMinutes; }

    }

    @Schema(name = "CallerCapabilities")
    public static class Caller {

        private final String role;
        private final int contextCount;
        private final boolean crossUserAccess;
        private final boolean ledgerDeletion;

        public Caller(final String role, final int contextCount, final boolean crossUserAccess,
                      final boolean ledgerDeletion) {
            this.role = role;
            this.contextCount = contextCount;
            this.crossUserAccess = crossUserAccess;
            this.ledgerDeletion = ledgerDeletion;
        }

        @Schema(description = "The caller's role, read when the request is made.")
        public String getRole() { return role; }

        @Schema(description = "How many contexts the caller has, to compare with contexts.maxPerUser.")
        public int getContextCount() { return contextCount; }

        @Schema(description = "Whether the caller may reach other users' resources with owner and all_users: an "
                + "administrator, with ADMIN_CROSS_USER_ACCESS_ENABLED on. Always false for a user who is not an "
                + "administrator.")
        public boolean isCrossUserAccess() { return crossUserAccess; }

        @Schema(description = "Whether the caller may delete and purge ledger chains: an administrator, with "
                + "LEDGER_DELETION_ENABLED on. Always false for a user who is not an administrator.")
        public boolean isLedgerDeletion() { return ledgerDeletion; }

    }

}
