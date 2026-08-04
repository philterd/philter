# API Keys and Authentication

Every request to Philter's REST API must be authenticated with an API key. API keys are managed per user account.

## API key format

Philter API keys start with the prefix `sk_` followed by 32 alphanumeric characters, for example:

```
sk_abcdefghijklmnopqrstuvwxyz012345
```

Keys are stored only as a SHA-256 hash; Philter cannot recover the original key after it is created. A short prefix of each key is retained so you can recognize it in the dashboard.

## Authenticating a request

Send the API key in the HTTP `Authorization` header using the `Bearer` scheme on every API request:

```http
Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345
```

For example, redacting text with `curl`:

```
curl -k "https://localhost:8080/api/filter" \
  --data "George Washington lives in 90210." \
  -H "Content-type: text/plain" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345"
```

A request with a missing, malformed, or unknown key is rejected with `401 Unauthorized`. These failures are recorded in the [audit log](../auditing.md).

## Managing API keys

API keys are created and removed in the dashboard, under **My Account** → **API Keys**:

* **Create a key.** Choose the key's [scopes](#scopes), then Philter generates the key and shows it once. Use the **Copy** button to copy it to your clipboard, then store it securely; it cannot be retrieved again afterward.
* **Delete a key.** Deleting a key immediately revokes it: subsequent requests using that key are rejected with `401 Unauthorized`. Deletion is permanent and a key cannot be reactivated. The key record itself is retained (marked deleted) so that audit entries which reference the key id still resolve to it; deleted keys are not shown in the list. Generate a new key if you need access again.

A user may have more than one API key (for example, one per integration), which makes it possible to rotate or revoke a single key without disrupting others.

An API key's creation, deletion, and scope changes are recorded in the [audit log](../auditing.md).

A deactivated user's API keys are also rejected for as long as the account is deactivated, even though the keys themselves are not deleted; reactivating the user restores them. See [User Management](../dashboard.md#user-management).

## Scopes

Every API key carries a set of **scopes** naming what it may do. A request to an endpoint whose scope the key does not hold is refused with `403 Forbidden` and a message naming the missing scope. Grant a key only what its integration needs: a key that only submits text for redaction has no reason to be able to read policies or export a ledger.

Scopes can only narrow what the key's owner could already do, never widen it. Every other check still applies on top: an administrator-only operation needs the scope **and** the admin role, and reaching another user's data still needs the `owner` parameter, administrator rights, and `ADMIN_CROSS_USER_ACCESS_ENABLED`.

Two scopes are separated from the resources they belong to because they return the original sensitive values in the clear:

* `ledger:export` is separate from `ledger:read`, so a key can list and validate ledger chains without being able to export their plaintext.
* `reidentify` is its own scope rather than part of `redact`, so a key that redacts text cannot reverse a replacement.

### Scopes and the endpoints they cover

| Scope | Endpoints |
|-------|-----------|
| `redact` | `POST /api/explain`<br>`POST /api/filter` |
| `contexts:read` | `GET /api/contexts`<br>`GET /api/contexts/{name}`<br>`GET /api/contexts/{name}/entries`<br>`GET /api/contexts/{name}/entries/export` |
| `contexts:write` | `DELETE /api/contexts/{name}`<br>`DELETE /api/contexts/{name}/entries`<br>`DELETE /api/contexts/{name}/entries/{entryId}`<br>`POST /api/contexts`<br>`POST /api/contexts/{name}/entries/import`<br>`PUT /api/contexts/{name}` |
| `policies:read` | `GET /api/policies`<br>`GET /api/policies/{policyName}`<br>`GET /api/policies/{policyName}/diff`<br>`GET /api/policies/{policyName}/versions`<br>`GET /api/policies/{policyName}/versions/{revision}`<br>`POST /api/policies/compile` |
| `policies:write` | `DELETE /api/policies/{policyName}`<br>`POST /api/policies`<br>`POST /api/policies/{policyName}/rollback` |
| `lists:read` | `GET /api/lists`<br>`GET /api/lists/{name}`<br>`GET /api/redact-lists` |
| `lists:write` | `DELETE /api/lists/{list}`<br>`POST /api/lists/{list}`<br>`POST /api/redact-lists`<br>`PUT /api/redact-lists` |
| `documents:read` | `GET /api/documents`<br>`GET /api/documents/{documentId}`<br>`GET /api/documents/{documentId}/status` |
| `documents:write` | `DELETE /api/documents/{documentId}` |
| `ledger:read` | `GET /api/ledger`<br>`GET /api/ledger/{documentId}`<br>`GET /api/ledger/{documentId}/valid` |
| `ledger:export` | `GET /api/ledger/{documentId}/export` |
| `ledger:delete` | `DELETE /api/ledger`<br>`DELETE /api/ledger/{documentId}` |
| `holds:read` | `GET /api/holds`<br>`GET /api/holds/{reference}` |
| `holds:write` | `DELETE /api/holds/{reference}`<br>`POST /api/holds` |
| `reidentify` | `POST /api/reidentify` |

`/api/status`, `/api/health`, and `/api/signing-key` take no API key at all and therefore need no scope. See [Unauthenticated endpoints](#unauthenticated-endpoints).

### Choosing and changing scopes

Scopes are selected when a key is created, under **My Account** → **API Keys** → **New API Key**. Use **Edit scopes** on an existing key to change them: the key value itself does not change, so integrations keep working with the same credential, and the change takes effect on the next request.

A key must have at least one scope. A key with none can call nothing.

Every scope change is recorded in the [audit log](../auditing.md) as a security event, including the scopes the key held before and after, so the record shows whether a key was widened or narrowed.

## Bootstrapping an API key for automation

Creating a key in the dashboard is the normal path, but turnkey deployments (cloud marketplace images, Terraform, Docker Compose) often need a key without an interactive step. Set the `PHILTER_BOOTSTRAP_API_KEY` environment variable to a value of the form `sk_` followed by 32 alphanumeric characters, and Philter assigns that key to the `admin` user at startup.

The key is only seeded when the `admin` user has no API keys at all, counting both active and archived (deleted) keys. So it is created once on a fresh install, and once you have created a key of your own (or revoked the bootstrap key), it is never seeded again on a later restart.

While the bootstrap key is in use, Philter makes it visible so it does not become a forgotten, long-lived credential: the admin sees a warning on login, and the **API Keys** page shows a banner identifying the bootstrap key (including its value, read from the environment of the running instance) with a prompt to create your own key and delete it.

The bootstrap key is created with every scope, since it exists to provision a deployment before anyone has chosen what it should be limited to. Narrow it with **Edit scopes**, or replace it with a key scoped to what your automation actually needs.

Authentication stays fully enabled; the bootstrap key is your own secret, provisioned the same way you supply other secrets. Treat it like any credential and rotate or revoke it in the dashboard once it is no longer needed. See [Settings](../settings.md#api-access).

## Restricting access by IP address

You can optionally restrict which client IP addresses may call the API with the `API_IP_ALLOWLIST` environment variable (see [Settings](../settings.md#api-access)). When set, an otherwise-authenticated request from an address that is not on the allowlist is rejected with `403 Forbidden` and the denial is recorded in the [audit log](../auditing.md).

## Unauthenticated endpoints

A small number of endpoints do not require an API key:

* `/api/status` and `/api/health` (the status/health endpoints).
* `/v3/api-docs` and `/swagger-ui/` (the OpenAPI specification and Swagger UI).

All other `/api/` endpoints require a valid API key.

## Transport security

Philter's API is served over HTTPS. Cloud marketplace deployments use a self-signed certificate by default, which is why the examples above pass `-k` to `curl`. Use a certificate trusted by your clients in production.

## See also

* [Developers](../developers/developers.md)
* [Auditing](../auditing.md)
* [Settings](../settings.md)
