# Users API

These endpoints create and manage users and create API keys for them, so a deployment can be administered without the dashboard: by automation such as CI, a marketplace image, or an infrastructure-as-code run, or by a separate user interface.

Users have no password. They authenticate with [API keys](../../account/api_keys.md), so no endpoint here accepts or returns a password, password hash, or MFA secret.

Every endpoint requires an administrator in addition to its [scope](../../account/api_keys.md#scopes), except `GET /api/users/me`, which any key holding `users:read` can call. A request that lacks the scope and a request from a non-administrator are both refused with `403 Forbidden`; the message says which.

There is no setting that turns these endpoints off. A headless deployment is administered through them, so an administrator's API key is the credential that manages it, and they are guarded like every other administrator endpoint: by the administrator role and a scope. Limit which keys hold `users:write` and `api-keys:write`.

The first administrator key comes from [`PHILTER_BOOTSTRAP_API_KEY`](../../account/api_keys.md#bootstrapping-an-api-key-for-automation). These endpoints are how that key provisions the rest.

## The user object

```json
{
  "username": "ci",
  "email": "ci@example.com",
  "role": "user",
  "active": true,
  "created": "2026-10-05T14:03:11.000Z",
  "deactivatedAt": null
}
```

* `role` - `user` or `admin`.
* `active` - `false` once the user is deactivated. A deactivated user's API keys are rejected.
* `deactivatedAt` - When the user was deactivated, or `null`.

## List users

```
GET /api/users?offset=0&limit=25
```

Returns users sorted by username, including deactivated users, and the total number of users. Requires `users:read` and an administrator. `limit` is capped at 100.

```json
{
  "users": [ { "username": "ci", "email": "ci@example.com", "role": "user", "active": true, "created": "2026-10-05T14:03:11.000Z", "deactivatedAt": null } ],
  "total": 1
}
```

## Get the calling key's user

```
GET /api/users/me
```

Returns the user that owns the API key making the request. Requires `users:read`; does not require an administrator. `me` is reserved and cannot be used as a username.

## Get a user

```
GET /api/users/{username}
```

Returns one user, active or deactivated. Requires `users:read` and an administrator. Returns `404 Not Found` if there is no such user.

## Create a user

```
POST /api/users
```

Creates a user with a default policy and a default context. Requires `users:write` and an administrator.

```json
{
  "username": "ci",
  "email": "ci@example.com",
  "role": "user"
}
```

* `username` (required) - Must not already belong to a user, including a deactivated one holding the name in reserve, and must not be `me`.
* `email` (optional) - The user's email address.
* `role` (optional) - `user` (the default) or `admin`.

A request that includes `password` is refused with `400 Bad Request`.

`201 Created`:

```json
{
  "username": "ci",
  "role": "user"
}
```

| Status | Meaning |
|--------|---------|
| 400 | The username is missing or is `me`, the role is not `user` or `admin`, or a password was sent. |
| 409 | A user with that username already exists, active or deactivated. |

```
curl -k "https://localhost:8080/api/users" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345" \
  -H "Content-Type: application/json" \
  --data '{"username":"ci","role":"user"}'
```

## Set a user's role

```
PUT /api/users/{username}/role
```

```json
{
  "role": "admin"
}
```

Sets the role to `user` or `admin` and returns the user. Requires `users:write` and an administrator.

| Status | Meaning |
|--------|---------|
| 400 | The role is missing or is not `user` or `admin`. |
| 404 | There is no user with that username. |
| 409 | The user is the last active administrator. Make another user an administrator first. |

## Deactivate a user

```
POST /api/users/{username}/deactivate
```

Deactivates the user and returns it. The user's API keys stop working at once. The user and all of its data (API keys, policies, contexts, lists, and redaction ledger) are retained, so the user can be reactivated. Requires `users:write` and an administrator.

| Status | Meaning |
|--------|---------|
| 404 | There is no user with that username. |
| 409 | The user is already deactivated, is the calling key's own user, or is the last active administrator. |

## Reactivate a user

```
POST /api/users/{username}/reactivate
```

Reactivates a deactivated user, restoring its API keys, and returns the user. Requires `users:write` and an administrator.

| Status | Meaning |
|--------|---------|
| 404 | There is no user with that username. |
| 409 | The user is already active. |

## Create an API key for a user

```
POST /api/users/{username}/api-keys
```

Mints a key for the named user with the scopes given in the body. Requires the `api-keys:write` scope and an administrator.

The requested scopes must be a subset of those the calling key holds. A key cannot grant a scope it does not carry, so the credential making the request bounds every credential it can create.

The key value is returned once, in this response. Philter stores only its SHA-256 hash and cannot recover it afterwards.

```json
{
  "scopes": ["redact"]
}
```

* `scopes` (required) - At least one [scope](../../account/api_keys.md#scopes). There is no default: a key carries what is asked for here and nothing else.

`201 Created`:

```json
{
  "username": "ci",
  "apiKey": "sk_abcdefghijklmnopqrstuvwxyz012345",
  "scopes": ["redact"]
}
```

| Status | Meaning |
|--------|---------|
| 400 | No scopes were given, or one of them is not a scope. |
| 403 | The key does not hold `api-keys:write`, the caller is not an administrator, or a requested scope is not held by the calling key. The message says which. |
| 404 | There is no active user with that username. |

```
curl -k "https://localhost:8080/api/users/ci/api-keys" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345" \
  -H "Content-Type: application/json" \
  --data '{"scopes":["redact"]}'
```

## Errors common to every endpoint

| Status | Meaning |
|--------|---------|
| 401 | The `Authorization` header is absent or the API key is not recognized. |
| 403 | The key does not hold the endpoint's scope, or the caller is not an administrator. The message says which. |

## Auditing

| Event | Recorded when |
|-------|---------------|
| `user_created` | A user was created. The principal is the calling administrator, the associated object is the new user, and the details name the calling API key. |
| `user_role_changed` | A role was set. The principal is the calling administrator, the associated object is the user, and the details name the calling API key. |
| `user_deactivated` | A user was deactivated. The principal is the calling administrator, the associated object is the user, and the details name the calling API key. |
| `user_reactivated` | A user was reactivated. The principal is the calling administrator, the associated object is the user, and the details name the calling API key. |
| `api_key_created` | A key was created. The principal is the key, the associated object is the user it belongs to, and the details name the administrator and the API key that asked for it. |

These are security events, so they cannot be switched off. They are readable through [`GET /api/audit`](audit_api.md). See [Auditing](../../auditing.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [Audit Log API](audit_api.md)
