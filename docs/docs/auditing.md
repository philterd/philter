# Auditing

Philter records security-relevant actions to an audit log so you can review who did what, and when. The audit log is written to MongoDB and is intended to support security review, compliance, and incident investigation.

## Where audit events are stored

Audit events are written to the `audit_events` collection in Philter's MongoDB database (see [Database](database.md)). Each event is a document with the following fields:

| Field | Description |
|-------|-------------|
| `event` | The type of action (one of the event names listed below). |
| `request_id` | A correlation id for the request or operation that produced the event. |
| `api_key_id` | The acting principal: the user id (for dashboard actions) or API key id, when known. |
| `associated_object` | The id of the entity the action concerned (for example, the policy or user affected), when applicable. |
| `client_ip_address` | The client IP address, when available. |
| `details` | A short, non-sensitive description with extra context (for example, a role name or a counter). |
| `timestamp` | When the event occurred. |

Audit events never contain the sensitive values themselves. For example, when a ledger is searched, the audit record stores a hash of the search term rather than the term; webhook configuration changes are recorded without the webhook URL or secret.

Writing an audit event can never fail the operation being audited: if the audit write fails, the failure is logged and the original operation still completes.

## What is audited

The audit log focuses on actions that change state or affect security, plus authentication failures. The events Philter emits are grouped below.

### Authentication

| Event | When it is recorded |
|-------|---------------------|
| `api_authentication_failed` | A request was rejected because the API key was missing, malformed, or unknown. |
| `api_ip_blocked` | A request was rejected because the client IP is not permitted by `API_IP_ALLOWLIST`. |
| `admin_cross_user_access` | An admin acted on another user's resource via the `owner` parameter (subject = the acting admin, associated object = the affected user). |

### Users

| Event | When it is recorded |
|-------|---------------------|
| `user_created` | A user account was created. |
| `user_password_changed` | A user's password was changed. |
| `user_role_changed` | A user's role was changed. |
| `user_deactivated` | A user account was deactivated: sign-in and API access are revoked, but the user record and all of its data are retained (the event detail records this). Deactivation never cascades, so governance evidence (the user's policies and redaction ledger) is preserved and stays resolvable to the retained user, and the account can be reactivated. |
| `user_reactivated` | A previously deactivated user account was reactivated, restoring sign-in and API access. |
| `user_deleted` | A user account was deleted (legacy event; current versions deactivate users rather than deleting them). |

### API keys

| Event | When it is recorded |
|-------|---------------------|
| `api_key_created` | An API key was created. |
| `api_key_deleted` | An API key was deleted (soft-deleted): it is revoked and can no longer authenticate, but the key record is retained so audit entries that reference its id still resolve. |

### Policies

| Event | When it is recorded |
|-------|---------------------|
| `policy_created` | A policy was created. |
| `policy_updated` | A policy was updated. |
| `policy_deleted` | A policy was deleted. |

### Contexts and custom lists

| Event | When it is recorded |
|-------|---------------------|
| `contexts_retrieved` | The list of contexts was retrieved. |
| `context_created` | A context was created. |
| `context_deleted` | A context was deleted. |
| `context_entry_deleted` | A single context entry was deleted. |
| `context_entries_purged` | All entries were cleared from a context. |
| `context_entries_exported` | A context's mapping table was exported. |
| `context_entries_imported` | A mapping table was imported into a context. |
| `context_entries_export_denied` | An export was denied because the caller is not the context's creator or an admin (or the context does not exist). |
| `context_entries_import_denied` | An import was denied because the caller is not the context's creator or an admin (or the context does not exist). |
| `custom_lists_retrieved` | The list of custom lists was retrieved. |
| `custom_list_items_retrieved` | The items in a custom list were retrieved. |
| `custom_list_created` | A custom list was created. |
| `custom_list_updated` | A custom list was updated. |
| `custom_list_deleted` | A custom list was deleted. |

### Documents and redaction

| Event | When it is recorded |
|-------|---------------------|
| `document_redaction_initiated` | A document was submitted for asynchronous redaction. |
| `document_redaction_completed` | A redaction completed. |
| `redacted_file_download` | A redacted document was downloaded. |
| `redacted_file_deleted` | An asynchronous redaction record was deleted. |
| `redaction_ledger_query` | The redaction ledger was queried or searched. |
| `redaction_ledger_deleted` | Ledger entries were deleted (by document or by retention). |
| `redaction_ledger_exported` | A ledger chain was exported. |
| `redaction_reversed` | A cryptographic redaction was reversed via `/api/reidentify`. See [Re-identification](#re-identification) below. |

For these events the `details` field carries extra context: `document_redaction_initiated` records the name and pinned version of the policy applied (along with the input and output content types), while `document_redaction_completed` records the number of redactions performed and the name and version of the policy that governed them.

### Re-identification

The `redaction_reversed` event is recorded every time `/api/reidentify` is called, regardless of whether individual values succeed or fail. Its `details` field records:

- `strategy` — `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE`.
- `requested` — the number of values submitted.
- `succeeded` — the number successfully decrypted.
- `reason` — the caller's verbatim stated authority for the reversal.
- `values` — the list of encrypted input values (ciphertexts). These are the replacement tokens, not the decrypted originals; the originals are **never** written to the audit log.
- `owner` — the target user id, present only when an admin used the `owner` parameter to act on behalf of another user.

This provides a full, auditable history of who un-redacted what, when, and under what stated authority. See [Re-identification](redaction/re-identification.md) for the full endpoint documentation.

### Account configuration

| Event | When it is recorded |
|-------|---------------------|
| `redact_lists_retrieved` | The account's always-redact / never-redact lists were retrieved. |
| `redact_lists_updated` | The account's always-redact / never-redact lists were changed. |
| `webhook_configured` | A webhook URL and secret were configured. |
| `webhook_removed` | The webhook was removed. |
| `settings_updated` | Account settings (such as the redaction ledger toggle) were changed. |

## Exporting the audit log

Administrators can export the audit log as a CSV file from the dashboard: open **Admin → Audit Log**, choose a date range, and click **Download Audit Log (CSV)**. This is an admin-only feature.

* **Date range with a 30-day limit.** Pick a **From** and a **To** date. The range may span at most **30 days**; a wider range (or a From date after the To date) disables the download and shows an error. The default range is the last 30 days.
* **Server time zone.** The **From** and **To** values are whole calendar days interpreted in the **server's time zone** (the JVM default), not the browser's. The **To** day is included in full — the export covers `From 00:00` up to, but not including, the start of the day after `To`, in server-local time.
* **Contents.** The CSV has a header row followed by one row per event, newest first, with the columns `timestamp`, `event`, `request_id`, `api_key_id`, `associated_object`, `client_ip_address`, and `details` (the same fields described above; timestamps are written in ISO-8601). As with the stored events, no sensitive values are included.
* **Size cap.** An export contains at most 100,000 events within the selected range (newest first). Narrow the range if you need to be sure you have captured everything in a busy period.

## Reviewing the audit log

The audit log is stored in MongoDB and can be queried with standard MongoDB tooling. For example, to see the most recent events:

```
db.audit_events.find().sort({ timestamp: -1 }).limit(50)
```

To find all failed authentication attempts from a given IP address:

```
db.audit_events.find({ event: "api_authentication_failed", client_ip_address: "203.0.113.10" })
```

## See also

* [Database](database.md)
* [Redaction Ledgers](redaction/ledgers.md) for the separate, cryptographically-verifiable record of individual redactions.
* [API Keys and Authentication](account/api_keys.md)
