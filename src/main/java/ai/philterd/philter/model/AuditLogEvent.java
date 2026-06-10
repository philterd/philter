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
package ai.philterd.philter.model;

public enum AuditLogEvent {

    CUSTOM_LISTS_RETRIEVED("custom_lists_retrieved"),
    CUSTOM_LIST_ITEMS_RETRIEVED("custom_list_items_retrieved"),
    CUSTOM_LIST_CREATED("custom_list_created"),
    CUSTOM_LIST_UPDATED("custom_list_updated"),
    CUSTOM_LIST_DELETED("custom_list_deleted"),

    CONTEXTS_RETRIEVED("contexts_retrieved"),
    CONTEXT_CREATED("context_created"),
    CONTEXT_DELETED("context_deleted"),

    POLICY_RETRIEVED("policy_retrieved"),
    POLICIES_RETRIEVED("policies_retrieved"),
    POLICY_CREATED("policy_created"),
    POLICY_UPDATED("policy_updated"),
    POLICY_DELETED("policy_deleted"),
    // Emitted when a policy is saved via the API and immediately becomes active. Records the calling
    // API key so the activation is attributable even when no approval step exists.
    POLICY_ACTIVATED("policy_activated"),
    POLICY_VERSION_HISTORY_RETRIEVED("policy_version_history_retrieved"),
    POLICY_ROLLED_BACK("policy_rolled_back"),

    LEDGER_RETRIEVED("ledger_retrieved"),
    LEDGER_DELETED("ledger_deleted"),

    REDACTED_FILE_SUMMARY_DOWNLOAD("redacted_file_summary_download"),
    REDACTED_FILE_DOWNLOAD("redacted_file_download"),
    REDACTED_DATA_SET_SUBMISSION("redacted_data_set_submission"),
    REDACTED_FILE_RETRIEVED("redacted_file_retrieved"),

    REDACTED_FILE_UPLOAD("redacted_file_upload"),
    DOCUMENT_REDACTION_INITIATED("document_redaction_initiated"),
    DOCUMENT_REDACTION_COMPLETED("document_redaction_completed"),
    REDACTED_FILE_DELETED("redacted_file_deleted"),
    REDACTED_DATA_SET_DELETED("redacted_data_set_deleted"),

    REDACTION_LEDGER_QUERY("redaction_ledger_query"),
    REDACTION_LEDGER_DELETED("redaction_ledger_deleted"),
    REDACTION_LEDGER_EXPORTED("redaction_ledger_exported"),

    API_KEY_CREATED("api_key_created"),
    API_KEY_DELETED("api_key_deleted"),

    // Authentication outcomes recorded by the API authentication filter.
    API_AUTHENTICATION_FAILED("api_authentication_failed"),
    API_IP_BLOCKED("api_ip_blocked"),

    // An admin acted on another user's resource (via the owner parameter). Recorded with the acting
    // admin as the subject and the affected user as the associated object.
    ADMIN_CROSS_USER_ACCESS("admin_cross_user_access"),

    // User account lifecycle.
    USER_CREATED("user_created"),
    USER_PASSWORD_CHANGED("user_password_changed"),
    USER_ROLE_CHANGED("user_role_changed"),
    USER_DEACTIVATED("user_deactivated"),
    USER_REACTIVATED("user_reactivated"),
    USER_MFA_ENABLED("user_mfa_enabled"),
    USER_MFA_DISABLED("user_mfa_disabled"),
    USER_MFA_LOCKED("user_mfa_locked"),
    USER_MFA_UNLOCKED("user_mfa_unlocked"),
    // Retained for historical events: users are deactivated rather than deleted.
    USER_DELETED("user_deleted"),

    // Account and admin configuration changes.
    WEBHOOK_CONFIGURED("webhook_configured"),
    WEBHOOK_REMOVED("webhook_removed"),
    REDACT_LISTS_RETRIEVED("redact_lists_retrieved"),
    REDACT_LISTS_UPDATED("redact_lists_updated"),

    // Context dictionary mutations.
    CONTEXT_ENTRY_DELETED("context_entry_deleted"),
    CONTEXT_ENTRIES_PURGED("context_entries_purged"),
    CONTEXT_ENTRIES_EXPORTED("context_entries_exported"),
    CONTEXT_ENTRIES_IMPORTED("context_entries_imported"),
    CONTEXT_ENTRIES_EXPORT_DENIED("context_entries_export_denied"),
    CONTEXT_ENTRIES_IMPORT_DENIED("context_entries_import_denied"),

    // A governed, audited reversal of a cryptographically-redacted value via /api/reidentify.
    REDACTION_REVERSED("redaction_reversed"),

    // Legal hold lifecycle. Holds block deletion and purge of governance evidence until released.
    // LEGAL_HOLD_BLOCKED_DELETION is recorded every time a delete or purge is prevented by an
    // active hold so there is an auditable record that the hold was enforced.
    LEGAL_HOLD_SET("legal_hold_set"),
    LEGAL_HOLD_RELEASED("legal_hold_released"),
    LEGAL_HOLD_BLOCKED_DELETION("legal_hold_blocked_deletion"),

    // Settings
    SETTINGS_UPDATED("settings_updated"),

    // Output signing key lifecycle.
    SIGNING_KEY_GENERATED("signing_key_generated"),
    SIGNING_KEY_REGENERATED("signing_key_regenerated");

    private final String auditLogEvent;

    AuditLogEvent(final String auditLogEvent) {
        this.auditLogEvent = auditLogEvent;
    }

    public String getAuditLogEvent() {
        return auditLogEvent;
    }

    @Override
    public String toString() {
        return auditLogEvent;
    }

}
