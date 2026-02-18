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

    CHANGESET_APPLIED("changeset_applied"),

    API_KEY_CREATED("api_key_created"),
    API_KEY_DELETED("api_key_deleted");

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
