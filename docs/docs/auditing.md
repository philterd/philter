# Auditing

Philter records security-relevant actions to an audit log so you can review who did what, and when. The audit log is written to MongoDB and is intended to support security review, compliance, and incident investigation.


Audit events are either **security** events or **redaction-activity** events. Security events are
always recorded. The two per-redaction events (`document_redaction_initiated` and
`document_redaction_completed`) can be switched off with `AUDIT_REDACTION_EVENTS_ENABLED=false`; see
[Settings](settings.md#auditing).

Audit logging is **always on**: there is no setting to enable or disable it.

## Where audit events are stored

Audit events are written to the `audit_events` collection in Philter's MongoDB database (see [Database](database.md)). Each event is a document with the following fields:

| Field | Description |
|-------|-------------|
| `event` | The type of action (one of the event names listed below). |
| `request_id` | A correlation id for the request or operation that produced the event. |
| `api_key_id` | The principal the event is recorded under, when known. Usually the calling user's id; administrative changes name the calling API key in `details`. Some events record the entity concerned instead: the policy for `policy_created` and `policy_updated`, the list for `custom_list_created`, `custom_list_updated`, and `custom_list_deleted`, and the key acted on for `api_key_*` events. |
| `associated_object` | The id of the entity the action concerned (for example, the policy or user affected), when applicable. |
| `client_ip_address` | The client IP address, when available. For an API request, it is the address of the connection or, for a request from a [trusted proxy](settings.md#api-access), the client address its `X-Forwarded-For` header names. Some events record where the change came from (such as `api` or `system`) in this field instead of an address. |
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
| `sign_in_succeeded` | A person [signed in](api_and_sdks/api/sign_in_api.md) with a username and password. The subject is the user, the associated object is the session key issued, and the detail names the username and the key. Records the client IP address, never the password. |
| `sign_in_failed` | A sign-in was refused: a wrong password, an unknown username, a user without a password, or a deactivated user. The detail names the username that was tried, cut to 100 characters, with commas and control characters replaced by `_`. Records the client IP address, never the password. |
| `sign_in_locked` | Repeated failed sign-ins locked a username ([lockout](api_and_sdks/api/sign_in_api.md#lockout-and-rate-limiting)). The detail names the username, cut and cleaned as for `sign_in_failed`, the limit, and the lockout period. Records the client IP address. Recorded once per lock. |
| `sign_in_rate_limited` | A client address went over the sign-in rate limit. The detail names the limit. Recorded for the first refused request in each minute, so a flood of requests is not a flood of events. |
| `admin_cross_user_access` | An admin acted on another user's resource via the `owner` parameter (subject = the acting admin, associated object = the affected user), or listed every user's resources with `all_users` (subject = the acting admin, no associated object; the detail names the listing). |

### Users

| Event | When it is recorded |
|-------|---------------------|
| `user_created` | A user account was created. Created through the [Users API](api_and_sdks/api/users_api.md), the subject is the calling administrator, the associated object is the new user, and the detail names the calling API key; for the `admin` user created at startup, the new user is both. |
| `user_role_changed` | A user's role was changed. Changed through the [Users API](api_and_sdks/api/users_api.md), the subject is the calling administrator, the associated object is the user, and the detail names the calling API key. |
| `user_deactivated` | A user account was deactivated: API access is revoked, but the user record and all of its data are retained (the event detail records this). Deactivation never cascades, so governance evidence (the user's policies and redaction ledger) is preserved and stays resolvable to the retained user, and the account can be reactivated. |
| `user_reactivated` | A previously deactivated user account was reactivated, restoring API access. Through the [Users API](api_and_sdks/api/users_api.md), deactivation and reactivation name the calling administrator as the subject and the calling API key in the detail. |
| `user_password_set` | A user without a password was given one, at creation or by an administrator through the [Users API](api_and_sdks/api/users_api.md#set-or-reset-a-users-password). The subject is the calling administrator, the associated object is the user, and the detail names the calling API key and whether a change is required. Never includes the password. |
| `user_password_reset` | An administrator replaced a user's password. Recorded like `user_password_set`. |
| `user_password_changed` | A user changed their own password. The subject and associated object are the user, and the detail names the calling API key. Never includes either password. |
| `user_mfa_enrolled` | A user confirmed [TOTP MFA](api_and_sdks/api/users_api.md#multi-factor-authentication) enrollment. The subject and associated object are the user, and the detail names the calling API key. Never includes the secret. |
| `user_mfa_removed` | A user's MFA enrollment was removed, by the user with a code or by an administrator, which also clears a lock. The subject is whoever removed it, and the detail names the calling API key. |
| `user_mfa_locked` | A user's MFA was locked after five consecutive bad codes. Recorded once per lock. |
| `user_mfa_unlocked` | An administrator unlocked a user's MFA. The subject is the administrator, and the detail names the calling API key. |

### API keys

| Event | When it is recorded |
|-------|---------------------|
| `api_key_created` | An API key was created. The subject is the key and the associated object is the user it belongs to. Created through the [API Keys API](api_and_sdks/api/api_keys_api.md), the detail names the user and the API key that asked for it. |
| `api_key_deleted` | An API key was deleted (soft-deleted): it is revoked and can no longer authenticate, but the key record is retained so audit entries that reference its id still resolve. Revoked through the [API Keys API](api_and_sdks/api/api_keys_api.md), the detail names the calling user and API key. |
| `api_key_scopes_changed` | An API key's [scopes](account/api_keys.md#scopes) were changed. The entry records the scopes the key held before and after, so it shows whether the key was widened or narrowed. Changed through the [API Keys API](api_and_sdks/api/api_keys_api.md), the detail also names the calling user and API key. |
| `api_key_expired` | A [session key](account/api_keys.md#session-keys) passed its idle timeout or maximum lifetime and was revoked. The subject is the key, the associated object is its user, and the detail gives the reason. Recorded once, by whichever instance notices first. |

### Policies

| Event | When it is recorded |
|-------|---------------------|
| `policy_created` | A policy was created, through `POST /api/policies` or `POST /api/policies/{name}/copy`. |
| `policy_updated` | A policy was updated, through `POST /api/policies` naming an existing policy, or its description or notes through `PUT /api/policies/{name}/details` (details name the fields changed). |
| `policy_activated` | A policy was saved via the API and became active immediately. Recorded alongside `policy_created` or `policy_updated`, and attributing the change to the API key that made it. Details include the policy name. |
| `policy_deleted` | A policy was deleted. The associated object is the deleted policy; details include the policy name and where the deletion came from. |
| `policy_version_history_retrieved` | The version history of a policy was retrieved. Details include the policy name and the number of versions returned. |
| `policy_rolled_back` | A policy was rolled back to a prior revision. The associated object is the policy; details include the policy name, the target revision, and the new revision number. |

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

- `strategy`: `CRYPTO_REPLACE` or `FPE_ENCRYPT_REPLACE`.
- `requested`: the number of values submitted.
- `succeeded`: the number successfully decrypted.
- `reason`: the caller's verbatim stated authority for the reversal.
- `values`: the list of encrypted input values (ciphertexts). These are the replacement tokens, not the decrypted originals. The originals are **never** written to the audit log.
- `owner`: the target user id, present only when an admin used the `owner` parameter to act on behalf of another user.

This provides a full, auditable history of who un-redacted what, when, and under what stated authority. See [Re-identification](redaction/re-identification.md) for the full endpoint documentation.

### Legal holds

| Event | When it is recorded |
|-------|---------------------|
| `legal_hold_set` | A legal hold was created. The `details` field includes the hold's reference, scope type, and scope value. |
| `legal_hold_released` | A legal hold was released. The `details` field includes the hold's reference. |
| `legal_hold_blocked_deletion` | A deletion was blocked because one or more active holds cover the data. The `details` field lists the references of all blocking holds. This event is recorded on every blocked attempt: per-document delete, bulk purge, and age-based purge. |

See [Legal Holds](redaction/legal_holds.md) for full documentation on the hold lifecycle and how holds block deletions.

### Output signing

| Event | When it is recorded |
|-------|---------------------|
| `signing_key_generated` | A new ES256 keypair was auto-generated on first start (no existing key was found in MongoDB). |
| `signing_key_regenerated` | The signing key was regenerated through `POST /api/signing-key/regenerate`. Any consumer that cached the old public key will need to re-fetch the new one from `GET /api/signing-key`. |

See [Output Signing](output_signing.md) for the full documentation on key management and response verification.

### Account configuration

| Event | When it is recorded |
|-------|---------------------|
| `redact_lists_retrieved` | The account's always-redact / never-redact lists were retrieved. |
| `redact_lists_updated` | The account's always-redact / never-redact lists were changed. |
| `webhook_configured` | A webhook URL and secret were configured. The URL and secret are not recorded. Set through the [API](api_and_sdks/api/webhooks.md#set-the-webhook), the subject is the calling user and the detail names the calling API key. |
| `webhook_removed` | The webhook was removed. Removed through the [API](api_and_sdks/api/webhooks.md#remove-the-webhook), the subject is the calling user and the detail names the calling API key. |
| `settings_updated` | An administrator changed the deployment settings. The `details` field names which settings changed (the webhook allowlist, output signing, or the Phield and Diffuse publishing settings) and never their values. Changed through the [Settings API](api_and_sdks/api/settings_api.md), the detail also names the calling API key. |

### Audit log access

| Event | When it is recorded |
|-------|---------------------|
| `audit_log_retrieved` | The audit log was read through `GET /api/audit`. The `details` field records the filters applied and how many events matched. Reading the log is audited like any other access to evidence. |
| `audit_log_exported` | The audit log was exported as CSV through `GET /api/audit/export`. The `details` field records the date range, time zone, how many events were exported, whether the export was truncated, and the calling API key. |

## Exporting the audit log

Administrators can export the audit log as a CSV file with [`GET /api/audit/export`](api_and_sdks/api/audit_api.md#export-audit-events-as-csv). It requires an administrator and the `audit:read` scope.

* **Date range with a 30-day limit.** `from` and `to` are required. `to` may be at most **30 days** after `from`; a wider range, or a `from` after `to`, is refused.
* **Time zone.** `from` and `to` are whole calendar days, read in the time zone given by the `zone` parameter, defaulting to the server's (the JVM default). The response reports the zone used. The `to` day is included in full, so the export covers `from 00:00` up to, but not including, the start of the day after `to`, in that time zone.
* **Contents.** The CSV has a header row followed by one row per event, newest first, with the columns `timestamp`, `event`, `request_id`, `api_key_id`, `associated_object`, `client_ip_address`, and `details` (the same fields described above; timestamps are written in ISO-8601, in UTC). As with the stored events, no sensitive values are included. A value beginning with `=`, `+`, `-`, `@`, a tab, or a carriage return is written with a leading apostrophe, so a spreadsheet shows it as text instead of running it as a formula. Most values are recorded by Philter itself, but some, such as a legal hold's reference, are written by callers.
* **Size.** The API returns the range one page at a time: `limit` events per page (default 100, at most 1,000), with `offset` and the `X-Philter-Export-Next-Offset` header to fetch the next; see [paging](api_and_sdks/api/audit_api.md#paging), including why pages of a range that includes the current day can repeat events.

## Reading the audit log over the API

`GET /api/audit` returns audit events as JSON so the log can be shipped to a SIEM, pulled for a
compliance review, or alerted on without a database connection. It requires an administrator and an
API key holding the `audit:read` scope, and each read records an `audit_log_retrieved` event of its
own. See the [Audit Log API](api_and_sdks/api/audit_api.md) for parameters and examples.

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
