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

### Users

| Event | When it is recorded |
|-------|---------------------|
| `user_created` | A user account was created. |
| `user_password_changed` | A user's password was changed. |
| `user_role_changed` | A user's role was changed. |
| `user_deleted` | A user account (and its data) was deleted. |

### API keys

| Event | When it is recorded |
|-------|---------------------|
| `api_key_created` | An API key was created. |
| `api_key_deleted` | An API key was deleted. |

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

### Account configuration

| Event | When it is recorded |
|-------|---------------------|
| `global_terms_updated` | The account's always-redact / never-redact term lists were changed. |
| `webhook_configured` | A webhook URL and secret were configured. |
| `webhook_removed` | The webhook was removed. |
| `settings_updated` | Account settings (such as the redaction ledger toggle) were changed. |

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
