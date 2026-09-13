# Provisioning API

Two endpoints create a user and mint an API key for a user the caller is not signed in as, so a
deployment can be provisioned by automation: CI, a marketplace image, an infrastructure-as-code run,
or a test harness.

**They are off by default and are not present unless the deployment opts in.** Set
`PROVISIONING_API_ENABLED=true` (see [Settings](../../settings.md#api-access)). With it unset, both
endpoints answer `404 Not Found` as though they were not there, and nothing reachable over the API
or in the database can turn them on: the switch is an environment variable, so changing it means
changing the deployment's configuration and restarting it. While it is on, Philter prints a warning
banner at startup naming what is enabled.

## Why they are off by default

Creating a credential in the dashboard forces the caller through whatever the login requires,
including [multi-factor authentication](../../login_security.md). An endpoint that mints a key is a
way around that second factor, so the dashboard stays the normal path and this is the exception a
deployment chooses deliberately.

Where the exception is taken, it is bounded:

* Neither endpoint creates an administrator.
* Neither grants a key a scope the calling key does not itself hold, so this cannot widen access
  beyond the credential making the request.
* Both require an administrator in addition to the scope.
* Every creation is recorded in the [audit log](../../auditing.md) with the acting administrator, and
  is readable through `GET /api/audit`.

The first key a deployment gets still comes from
[`PHILTER_BOOTSTRAP_API_KEY`](../../account/api_keys.md#bootstrapping-an-api-key-for-automation).
These endpoints are how that key provisions the rest.

## Create a user

```
POST /api/users
```

Creates a non-administrator user with a default policy and a default context, as the dashboard does.
Requires the `users:write` [scope](../../account/api_keys.md#scopes) and an administrator.

There is no role parameter. This endpoint cannot create an administrator; make one in the dashboard.

### Request

```json
{
  "username": "ci",
  "email": "ci@example.com",
  "password": "a-password-of-at-least-16-characters"
}
```

* `username` (required) - The username, which must not already belong to a user, including a
  deactivated one holding the name in reserve.
* `email` (optional) - The user's email address.
* `password` (required) - At least 16 characters, the same minimum the dashboard enforces.

### Response

`201 Created`:

```json
{
  "username": "ci",
  "role": "user"
}
```

### Errors

| Status | Meaning |
|--------|---------|
| 400 | The username is missing, or the password is missing or shorter than 16 characters. |
| 401 | The `Authorization` header is absent or the API key is not recognized. |
| 403 | The key does not hold `users:write`, or the caller is not an administrator. The message says which. |
| 404 | `PROVISIONING_API_ENABLED` is not set, so the endpoint is not present. |
| 409 | A user with that username already exists, active or deactivated. |

### Example

```
curl -k "https://localhost:8080/api/users" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345" \
  -H "Content-Type: application/json" \
  --data '{"username":"ci","password":"a-password-of-at-least-16-characters"}'
```

## Create an API key for a user

```
POST /api/users/{username}/api-keys
```

Mints a key for the named user with the scopes given in the body. Requires the `api-keys:write`
scope and an administrator.

The requested scopes must be a subset of those the calling key holds. A key cannot grant a scope it
does not carry, so the credential making the request bounds every credential it can create.

The key value is returned once, in this response. Philter stores only its SHA-256 hash and cannot
recover it afterwards.

### Request

```json
{
  "scopes": ["redact"]
}
```

* `scopes` (required) - At least one [scope](../../account/api_keys.md#scopes). There is no default:
  a key carries what is asked for here and nothing else.

### Response

`201 Created`:

```json
{
  "username": "ci",
  "apiKey": "sk_abcdefghijklmnopqrstuvwxyz012345",
  "scopes": ["redact"]
}
```

### Errors

| Status | Meaning |
|--------|---------|
| 400 | No scopes were given, or one of them is not a scope. |
| 401 | The `Authorization` header is absent or the API key is not recognized. |
| 403 | The key does not hold `api-keys:write`, the caller is not an administrator, or a requested scope is not held by the calling key. The message says which. |
| 404 | `PROVISIONING_API_ENABLED` is not set, so the endpoint is not present, or there is no active user with that username. |

### Example

```
curl -k "https://localhost:8080/api/users/ci/api-keys" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345" \
  -H "Content-Type: application/json" \
  --data '{"scopes":["redact"]}'
```

## Auditing

| Event | Recorded when |
|-------|---------------|
| `user_created` | A user was created. The principal is the calling administrator and the associated object is the new user. |
| `api_key_created` | A key was created. The principal is the key, the associated object is the user it belongs to, and the details name the administrator and the API key that asked for it. |

Both are security events, so they cannot be switched off. See [Auditing](../../auditing.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [Settings](../../settings.md#api-access)
* [Audit Log API](audit_api.md)
