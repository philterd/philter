# API

Philter has no built-in user interface: everything, including administration, is done through its API. A user interface signs people in with their own username and password through the [Sign-in API](api/sign_in_api.md) and uses a session key for each person, as [Philter UI](https://github.com/philterd/philter-ui), in development, is being built to; see [Sign-in Security](../sign_in_security.md).

Philter's API has the following sections:

* [Redaction API](api/filtering_api.md) - Submit text or PDFs for redaction. In Philter 4.0, PDF requests are asynchronous by default; the redacted bytes are downloaded via the Documents API.
* [Documents API](api/documents_api.md) - List, poll, download, and delete asynchronous PDF redactions.
* [Webhooks](api/webhooks.md) - Receive signed HTTP notifications when an asynchronous redaction completes or fails.
* [Policies API](api/policies_api.md) - Create, modify, and delete [policies](../policies/filter_policies.md).
* [Custom Lists API](api/custom_lists_api.md) - Create, modify, and delete custom lists. Custom lists are referenced by policies to identify terms to redact.
* [Always/Never Redact Lists API](api/redact_lists_api.md) - Get, replace (POST), and append to (PUT) the account's always-redact and never-redact lists, applied across all of your policies.
* [Contexts API](api/contexts_api.md) - Create, update, and delete contexts and inspect their entries. Contexts maintain referential integrity across documents.
* [Redaction Ledger API](api/ledger_api.md) - List, read, verify, export, and delete [redaction ledger](../redaction/ledgers.md) chains.
* [Legal Holds API](api/legal_holds_api.md) - Set, list, and release [legal holds](../redaction/legal_holds.md) that block deletion of evidence.
* [Users API](api/users_api.md) - Create, list, promote, deactivate, and reactivate users, and manage their passwords.
* [Sign-in API](api/sign_in_api.md) - Exchange a username and password for a session key. Disabled by default.
* [API Keys API](api/api_keys_api.md) - Create, re-scope, and revoke API keys.
* [Limits API](api/limits_api.md) - Read the limits and rules Philter enforces, and what the caller may do.
* [Settings API](api/settings_api.md) - Read and change the deployment's administrator settings.
* [Audit API](api/audit_api.md) - List and export the [audit log](../auditing.md).

## Listings

Every endpoint that lists things returns an object with the items on the requested page and `total`, how many items the listing has across every page, so a client can show a count and page through without guessing:

```json
{
  "policies": ["default", "claims-2025"],
  "total": 2
}
```

Listings share these query parameters:

* `offset` (default `0`) - How many items to skip.
* `limit` (default `25`, at most `100`) - How many items to return. A larger value is reduced to `100`.
* `sort` - The field to order by, from the listing's fields in the table below. An unknown field is refused with `400 Bad Request` naming the fields it accepts.
* `order` - `asc` or `desc`. Left out, the listing's default sort keeps its default direction, and any other sort is ascending. Anything else is refused with `400 Bad Request`.
* `q` - Where a listing supports it, only items whose field (see the table) contains `q`, ignoring case. `q` is matched as plain text, not as a pattern.

Items with the same value for the sort field are kept in a stable order, so paging does not skip or repeat them. `total` counts the items that match `q` and any filters.

| Listing | Items | `sort` (default first) | `q` searches | Other filters |
|---------|-------|------------------------|--------------|---------------|
| [`GET /api/policies`](api/policies_api.md#get-policy-names) | `policies` | `name`, `created`, `updated` | name | `all_users`, `managed`, `deleted` (the `managed` and `deleted` listings sort by `name` only) |
| [`GET /api/policies/{policyName}/versions`](api/policies_api.md#list-versions) | `versions` | `revision` (newest first) | | |
| [`GET /api/contexts`](api/contexts_api.md) | `contexts` | `name`, `created` | name | `all_users` |
| [`GET /api/contexts/{name}/entries`](api/contexts_api.md) | `entries` | `created` (newest first), `reads` | | |
| [`GET /api/lists`](api/custom_lists_api.md) | `lists` | `name` | name | `all_users` |
| [`GET /api/holds`](api/legal_holds_api.md) | `holds` | `set` (newest first), `reference` | reference | `all_users` |
| [`GET /api/documents`](api/documents_api.md) | `pendingRedactedDocuments` | `submitted` (newest first), `fileName` | | `status` |
| [`GET /api/ledger`](api/ledger_api.md) | `chains` | `created` (newest first), `filename` | document id or file name | `all_users` |
| [`GET /api/users`](api/users_api.md#list-users) | `users` | `username`, `created` | username | `role`, `active` |
| [`GET /api/api-keys`](api/api_keys_api.md#list-your-keys) and `GET /api/users/{username}/api-keys` | `apiKeys` | `created` (oldest first) | | `session` |
| [`GET /api/audit`](api/audit_api.md) | `events` | `timestamp` (newest first), by `order` only | | `event`, `from`, `to`, `owner` |
| [`GET /api/webhook/deliveries`](api/webhooks.md#list-the-deliveries) | `deliveries` | `created` (newest first), by `order` only | | |

## Errors

Every error Philter returns under `/api` is a JSON object with the same fields, whatever refused the request and whatever the request's `Accept` header asked for:

```json
{
  "message": "This API key does not have the 'policies:read' scope.",
  "reason": "missing_scope"
}
```

* `message` - What happened, written for a person to read. It may change between releases; do not match on it.
* `reason` - Why the request was refused, as a stable code. Use it in code to decide what to do.
* `field` - Present when a request is refused as invalid and the invalid parameter or body field is known, such as `sort` or `password`.

A refusal with a specific reason gives it, as listed below. Any other error gives the reason its status implies: `invalid_request` (400), `invalid_credentials` (401), `forbidden` (403), `not_found` (404), `method_not_allowed` (405), `not_acceptable` (406), `conflict` (409), `payload_too_large` (413), `unsupported_media_type` (415), `rate_limited` (429), `service_unavailable` (503), or `internal_error` (500).

| Status | `reason` | Meaning |
|--------|----------|---------|
| 401 | `invalid_credentials` | The API key is missing, malformed, or unknown, or is a revoked long-lived key. Sign-in also gives it for a wrong username or password, without saying which. |
| 401 | `session_expired` | The session key has ended: it passed its idle timeout or maximum lifetime, was revoked, or signed out. Sign in again. |
| 401 | `user_deactivated` | The API key's user is deactivated. |
| 403 | `missing_scope` | The API key does not hold the scope the endpoint requires. |
| 403 | `admin_required` | The operation requires an administrator. |
| 403 | `feature_disabled` | The operation is turned off in this deployment, such as ledger deletion. |
| 403 | `password_change_required` | The session key may only change its user's password until it is changed. |
| 403 | `mfa_enrollment_required` | The session key may only enroll its user in MFA until enrollment is confirmed. |
| 403 | `wrong_password` | The current password is not correct. |
| 400, 403 | `invalid_code` | The MFA code is not valid: 400 when confirming enrollment, 403 when removing it. |
| 403 | `mfa_locked` | The user's MFA is locked after repeated bad codes; an administrator must unlock it. |
| 403 | `scope_not_held` | The calling key cannot create a key, widen a key's scopes, or grant scopes it does not hold. |
| 404 | `not_found` | Nothing with that name, id, or path exists, or the `owner` does not exist or may not be reached. The two read the same, so a value cannot be used to discover what exists. |
| 409 | `self_action_refused` | The caller cannot do this to their own user or to the key making the request, such as deactivating themselves or revoking the calling key. |
| 409 | `last_admin` | The user is the last active administrator. |
| 409 | `already_exists` | A user with that username already exists, active or deactivated. |
| 409 | `mfa_unavailable`, `mfa_already_enrolled`, `mfa_not_enrolled` | MFA is not turned on in this deployment, or the user is already enrolled, or not enrolled. |
| 409 | `changed_concurrently` | Another request changed the same thing first, such as the password. Read it again and retry. |
| 409 | `not_a_session_key` | Only a session key can sign itself out. |
| 409 | `webhook_not_set` | No webhook is set, so there is nothing to test. |
| 409 | `externally_managed` | The signing key is managed outside Philter. |
| 409 | `context_in_use` | The context has queued or running redactions. |
| 409 | `document_not_ready` | The document is still being redacted. |
| 410 | `document_failed` | The document's redaction failed, so there is nothing to download. |

Some refusals carry their own reasons, documented with their endpoints: the policy conflicts (`policy_exists`, `policy_managed`, `policy_changed`, `policy_default`), `context_exists` and `context_limit_reached`, `list_exists`, `hold_exists` and `operation_in_progress`, `redact_list_changed`, `entry_unreadable`, and sign-in's `locked` and `rate_limited`.

## Request ids

Every response under `/api` carries an `X-Request-Id` header with an id Philter gives the request, whether the request succeeded or was refused:

```
HTTP/1.1 404
X-Request-Id: 01929f2e-6c3a-7b1d-9e4f-3a2b1c0d9e8f
Content-Type: application/json

{"message":"Not found.","reason":"not_found"}
```

Every [audit event](../auditing.md) the request causes records the same id as its `request_id`, so a client that keeps the id, or a person who quotes it, can find exactly what the request did with [`GET /api/audit`](api/audit_api.md). The exception is redaction itself: `document_redaction_initiated` and `document_redaction_completed` record the [document id](api/filtering_api.md) (`X-Document-Id`, or `documentId` for an asynchronous PDF) as their `request_id`, so a queued redaction's start and its later completion share one id with its ledger chain. Philter always makes its own id and does not take one from the request. A request refused by the web server before it reaches Philter, such as one with a malformed path, has no id.

## OpenAPI Specification

Philter's API is described by an OpenAPI specification generated from the application's source. The OpenAPI export integration test regenerates it and checks it against the registered routes and the committed copy. Run that test when changing an endpoint; a successful ordinary compilation alone does not refresh the published artifact. You can always find it in any of these places:

* **In this documentation:** [openapi.json](openapi.json). This is the copy built with these pages, from the same source revision, and needs no running instance.
* **In the GitHub repository:** [`docs/docs/api_and_sdks/openapi.json`](https://github.com/philterd/philter/blob/main/docs/docs/api_and_sdks/openapi.json). This is the committed copy, reflecting the latest code on `main`.
* **From a running Philter instance:** `https://<your-philter-host>:8080/v3/api-docs`. This is the live specification served by that instance, reflecting its version and configuration.

Use the specification to generate an API client for your language (for example with [OpenAPI Generator](https://openapi-generator.tech/)). See [Client SDKs](sdks.md) for more.

## Interactive API Reference (Swagger UI)

Every running Philter instance serves an interactive API reference (Swagger UI) generated from the live OpenAPI specification. It lists every endpoint with its parameters, request and response schemas, and lets you try requests directly from the browser.

* **Swagger UI:** `https://<your-philter-host>:8080/swagger-ui/index.html`

To authorize a request from Swagger UI, send your API key as a bearer token in the `Authorization` header (see [API Keys and Authentication](../account/api_keys.md)).

## Securing Philter's API

Philter serves its API over HTTPS, using a self-signed certificate generated on first start unless you supply your own. See [TLS](../settings.md#tls) for how to replace the certificate or terminate TLS in front of Philter.

## SDKs

The Philter [Java SDK](sdks.md) provides convenient methods for using Philter's API. For other languages, generate a client from the OpenAPI specification above. See [Client SDKs](sdks.md) for more information.

## Complete endpoint inventory

The [endpoint inventory](api/endpoint_inventory.md) lists every HTTP operation, its required scope, and its detailed reference. Additional API sections include the [Redaction Ledger API](api/ledger_api.md), [Legal Holds API](api/legal_holds_api.md), [policy history and rollback](api/policies_api.md#policy-version-history), [re-identification](../redaction/re-identification.md), and [public health and signing keys](api/public_api.md).
