# API Keys and Authentication

Every request to Philter's REST API must be authenticated with an API key. API keys are managed per user account.

## API key format

Philter API keys start with the prefix `sk_` followed by 32 alphanumeric characters, for example:

```
sk_abcdefghijklmnopqrstuvwxyz012345
```

Keys are stored only as a SHA-256 hash; Philter cannot recover the original key after it is created. A short prefix of each key is retained and returned when keys are listed, so you can tell them apart.

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

API keys are created, re-scoped, and revoked with the [API Keys API](../api_and_sdks/api/api_keys_api.md). A user interface does not need a key of its own: it [signs people in](../sign_in_security.md) with their own username and password, and uses the [session key](#session-keys) Philter issues for each person. [Philter UI](https://github.com/philterd/philter-ui), in development, is being built to work this way.

* **Create a key.** `POST /api/api-keys` creates a key for the calling key's user; an administrator can create one for another user with `POST /api/users/{username}/api-keys`. Name the key's [scopes](#scopes) in the request. The response contains the key once; store it securely, since it cannot be retrieved again.
* **Revoke a key.** `DELETE /api/api-keys/{keyId}` immediately revokes it: subsequent requests using that key are rejected with `401 Unauthorized`. Deletion is permanent and a key cannot be reactivated. The key record itself is retained (marked deleted) so that audit entries which reference the key id still resolve to it; revoked keys are not listed. Create a new key if you need access again.

A user may have more than one API key (for example, one per integration), which makes it possible to rotate or revoke a single key without disrupting others.

An API key's creation, deletion, and scope changes are recorded in the [audit log](../auditing.md).

A deactivated user's API keys are also rejected for as long as the account is deactivated, even though the keys themselves are not deleted; reactivating the user restores them. See the [Users API](../api_and_sdks/api/users_api.md).

## Scopes

Every API key carries a set of **scopes** naming what it may do. A request to an endpoint whose scope the key does not hold is refused with `403 Forbidden` and a message naming the missing scope. Grant a key only what its integration needs: a key that only submits text for redaction has no reason to be able to read policies or export a ledger.

Scopes can only narrow what the key's owner could already do, never widen it. Every other check still applies on top: an administrator-only operation needs the scope **and** the admin role, and reaching another user's data still needs the `owner` parameter, administrator rights, and `ADMIN_CROSS_USER_ACCESS_ENABLED`.

Two scopes are separated from the resources they belong to because they return the original sensitive values in the clear:

* `ledger:export` is separate from `ledger:read`, so a key can list and validate ledger chains without being able to export their plaintext.
* `reidentify` is its own scope rather than part of `redact`, so a key that redacts text cannot reverse a replacement.

`audit:read` stands apart for a different reason: the audit log spans the whole deployment rather than one resource, so it is not reachable with any resource's read scope. Reading it also requires an administrator.

`signing:write` covers rotating the output signing key, which affects every instance in the deployment. It also requires an administrator. The two signing-key read endpoints take no API key at all.

`users:read` and `users:write` cover the [Users API](../api_and_sdks/api/users_api.md). Both require an administrator, except that `users:read` lets any key read its own user through `GET /api/users/me`, and `users:write` lets any key change its own user's password through `PUT /api/users/me/password` and manage its own MFA enrollment under `/api/users/me/mfa`.

`api-keys:read` and `api-keys:write` cover the [API Keys API](../api_and_sdks/api/api_keys_api.md). A key can list, create, re-scope, and revoke its own user's keys; an administrator can also manage other users' keys. A key cannot grant a scope it does not hold, cannot change or revoke a key holding a scope it does not hold, and cannot revoke itself.

### Scopes and the endpoints they cover

| Scope | Endpoints |
|-------|-----------|
| `redact` | `POST /api/explain`<br>`POST /api/filter` |
| `contexts:read` | `GET /api/contexts`<br>`GET /api/contexts/{name}`<br>`GET /api/contexts/{name}/entries`<br>`GET /api/contexts/{name}/entries/export` |
| `contexts:write` | `DELETE /api/contexts/{name}`<br>`DELETE /api/contexts/{name}/entries`<br>`DELETE /api/contexts/{name}/entries/{entryId}`<br>`POST /api/contexts`<br>`POST /api/contexts/{name}/entries/import`<br>`PUT /api/contexts/{name}` |
| `policies:read` | `GET /api/policies`<br>`GET /api/policies/templates/{templateName}`<br>`GET /api/policies/{policyName}`<br>`GET /api/policies/{policyName}/details`<br>`GET /api/policies/{policyName}/diff`<br>`GET /api/policies/{policyName}/versions`<br>`GET /api/policies/{policyName}/versions/{revision}`<br>`POST /api/policies/compile` |
| `policies:write` | `DELETE /api/policies/{policyName}`<br>`POST /api/policies`<br>`POST /api/policies/{policyName}/copy`<br>`POST /api/policies/{policyName}/rollback`<br>`PUT /api/policies/{policyName}`<br>`PUT /api/policies/{policyName}/details` |
| `lists:read` | `GET /api/lists`<br>`GET /api/lists/{name}`<br>`GET /api/redact-lists`<br>`GET /api/redact-lists/{list}` |
| `lists:write` | `DELETE /api/lists/{name}`<br>`POST /api/lists/{name}`<br>`POST /api/redact-lists`<br>`PUT /api/lists/{name}`<br>`PUT /api/redact-lists`<br>`PUT /api/redact-lists/{list}` |
| `documents:read` | `GET /api/documents`<br>`GET /api/documents/{documentId}`<br>`GET /api/documents/{documentId}/status` |
| `documents:write` | `DELETE /api/documents/{documentId}` |
| `ledger:read` | `GET /api/ledger`<br>`GET /api/ledger/{documentId}`<br>`GET /api/ledger/{documentId}/valid` |
| `ledger:export` | `GET /api/ledger/{documentId}/export` |
| `ledger:delete` | `DELETE /api/ledger`<br>`DELETE /api/ledger/{documentId}` |
| `holds:read` | `GET /api/holds`<br>`GET /api/holds/{reference}` |
| `holds:write` | `DELETE /api/holds/{reference}`<br>`POST /api/holds` |
| `audit:read` | `GET /api/audit`<br>`GET /api/audit/export` |
| `signing:write` | `POST /api/signing-key/regenerate` |
| `users:read` | `GET /api/users`<br>`GET /api/users/me`<br>`GET /api/users/{username}` |
| `users:write` | `POST /api/users`<br>`POST /api/users/{username}/deactivate`<br>`POST /api/users/{username}/reactivate`<br>`PUT /api/users/{username}/role`<br>`PUT /api/users/{username}/email`<br>`PUT /api/users/{username}/password`<br>`PUT /api/users/me/password`<br>`POST /api/users/me/mfa`<br>`POST /api/users/me/mfa/confirm`<br>`POST /api/users/me/mfa/remove`<br>`DELETE /api/users/{username}/mfa`<br>`POST /api/users/{username}/mfa/unlock` |
| `api-keys:read` | `GET /api/api-keys`<br>`GET /api/users/{username}/api-keys` |
| `api-keys:write` | `DELETE /api/api-keys/{keyId}`<br>`DELETE /api/users/{username}/session-keys`<br>`POST /api/api-keys`<br>`POST /api/users/{username}/api-keys`<br>`PUT /api/api-keys/{keyId}/scopes` |
| `settings:read` | `GET /api/settings` |
| `settings:write` | `PATCH /api/settings` |
| `webhooks:read` | `GET /api/webhook`<br>`GET /api/webhook/deliveries` |
| `webhooks:write` | `DELETE /api/webhook`<br>`POST /api/webhook/test`<br>`PUT /api/webhook` |
| `reidentify` | `POST /api/reidentify` |

`/api/health` and `/api/signing-key` take no API key at all and therefore need no scope. See [Unauthenticated endpoints](#unauthenticated-endpoints).

### Endpoints any key can call

`DELETE /api/api-keys/current` signs out a [session key](#session-keys). Any key can call it, whatever its scopes, because it can only end the caller's own access.

`GET /api/api-keys/scopes` [lists the scopes](../api_and_sdks/api/api_keys_api.md#list-the-scopes) and what each allows. Any key can call it, because it describes the API rather than any account.

`GET /api/limits` [returns Philter's limits](../api_and_sdks/api/limits_api.md) and what the caller may do. Any key can call it, because it describes the API and the caller's own access.

### Choosing and changing scopes

Scopes are named when a key is created. Change them on an existing key with [`PUT /api/api-keys/{keyId}/scopes`](../api_and_sdks/api/api_keys_api.md#change-a-keys-scopes): the key value itself does not change, so integrations keep working with the same credential, and the change takes effect on the next request.

A key must have at least one scope. A key with none can call nothing. A key cannot change its own scopes, as it cannot revoke itself: change them with another key, so a client never cuts off the access it is using.

Every scope change is recorded in the [audit log](../auditing.md) as a security event, including the scopes the key held before and after, so the record shows whether a key was widened or narrowed.

## Session keys

A session key is an API key issued when a person [signs in](../api_and_sdks/api/sign_in_api.md) through a user interface, as opposed to a long-lived key used by automation. It holds every scope, with the user's role still deciding administrator access, but it cannot create API keys or widen an existing key's scopes, so a session cannot produce a credential that outlives it. A session key works like any other key until it expires:

* **Idle timeout.** It expires after [`SESSION_KEY_IDLE_TIMEOUT_MINUTES`](../settings.md#api-access) (default 30) without a request. Each request starts the timeout again.
* **Maximum lifetime.** It expires [`SESSION_KEY_MAX_LIFETIME_MINUTES`](../settings.md#api-access) (default 720, 12 hours) after it was issued, however active it is.

Each key records both limits when it is issued, so changing the settings applies to keys issued afterwards. Every request with a session key is checked against the database, not a cache, so a session key that has expired or been revoked is refused on every instance at once, with or without a shared cache. An expired key is revoked and recorded as `api_key_expired` in the [audit log](../auditing.md), whether it is presented again or found by a sweep that runs every minute; until then it is still listed, with its expiry in the past. Expiry is measured with each instance's clock, so keep the instances' clocks synchronized.

The holder signs out with [`DELETE /api/api-keys/current`](../api_and_sdks/api/api_keys_api.md#sign-out). An administrator revokes all of a user's session keys with [`DELETE /api/users/{username}/session-keys`](../api_and_sdks/api/api_keys_api.md#revoke-a-users-session-keys). Setting, changing, or resetting a user's password also revokes them. Key listings mark session keys with `"session": true` and give their expiry. Long-lived keys never expire.

## Bootstrapping an API key for automation

Every key is created with another key, so the first one comes from the environment. Set the `PHILTER_BOOTSTRAP_API_KEY` environment variable to a value of the form `sk_` followed by 32 alphanumeric characters, and Philter assigns that key to the `admin` user at startup. It is required until the `admin` user has had an API key, active or revoked: Philter does not start without it on a fresh install.

The key is only seeded when the `admin` user has no API keys at all, counting both active and revoked keys. So it is created once on a fresh install, and once the `admin` user has had any key, the variable is ignored and the key is never seeded again on a later restart.

The bootstrap key is listed with `"bootstrap": true` by the [API Keys API](../api_and_sdks/api/api_keys_api.md#the-key-object), and Philter logs a warning when it seeds it, so it does not become a forgotten, long-lived credential.

The bootstrap key is created with every scope, since it exists to provision a deployment before anyone has chosen what it should be limited to. Use it to create keys scoped to what each integration needs. To retire it, create a replacement administrator key holding every scope, then revoke the bootstrap key with the replacement: a key cannot revoke itself or a key holding scopes it lacks.

Authentication stays fully enabled; the bootstrap key is your own secret, provisioned the same way you supply other secrets. Treat it like any credential and retire it once it is no longer needed. See [Settings](../settings.md#api-access).

## Restricting access by IP address

Philter does not filter by client IP address. Restrict access at the network layer instead (security
groups, firewall rules, or your ingress or load balancer), where the rules apply to every port on the
host and cannot be influenced by the request itself. An application-level check sees only the address
the request claims to come from, which a client controls through forwarding headers.

## Unauthenticated endpoints

A small number of endpoints do not require an API key:

* `/api/health` (the health endpoint).
* `GET /api/sign-in`, `POST /api/sign-in`, and `POST /api/sign-in/mfa` ([password sign-in](../api_and_sdks/api/sign_in_api.md)). The `POST` endpoints take a username and password, or a challenge and code, instead of a key. All three answer `404 Not Found` unless `PASSWORD_SIGN_IN_ENABLED` is `true`.
* `/v3/api-docs` and `/swagger-ui/` (the OpenAPI specification and Swagger UI).
* `GET /api/signing-key` and `GET /api/signing-key/{keyId}` (the public [output signing](../output_signing.md) keys). Other requests under `/api/signing-key`, such as `POST /api/signing-key/regenerate`, require an API key.
* `/actuator/health` and `/actuator/prometheus` (see [Monitoring and Logging](../monitoring_and_logging.md)).

All other `/api/` endpoints require a valid API key.

## Transport security

Philter's API is served over HTTPS. Cloud marketplace deployments use a self-signed certificate by default, which is why the examples above pass `-k` to `curl`. Use a certificate trusted by your clients in production.

## See also

* [Developers](../developers/developers.md)
* [Auditing](../auditing.md)
* [Settings](../settings.md)
