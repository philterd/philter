# Users API

These endpoints create and manage users. They are how a deployment is administered: by automation such as CI, a marketplace image, or an infrastructure-as-code run, or by a separate user interface, such as [Philter UI](https://github.com/philterd/philter-ui) (in development).

Requests authenticate with [API keys](../../account/api_keys.md). Create and manage a user's keys with the [API Keys API](api_keys_api.md). A user can also have a password, for a person who signs in through a user interface; password sign-in itself is not available yet. A user without a password can only use API keys. No endpoint returns a password or its hash. See [Passwords](#passwords).

Every endpoint requires an administrator in addition to its [scope](../../account/api_keys.md#scopes), except `GET /api/users/me`, which any key holding `users:read` can call, and `PUT /api/users/me/password`, which any key holding `users:write` can call. A request that lacks the scope and a request from a non-administrator are both refused with `403 Forbidden`; the message says which.

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
  "deactivatedAt": null,
  "passwordSet": false,
  "passwordChangeRequired": false
}
```

* `role` - `user` or `admin`.
* `active` - `false` once the user is deactivated. A deactivated user's API keys are rejected.
* `deactivatedAt` - When the user was deactivated, or `null`.
* `passwordSet` - Whether the user has a password.
* `passwordChangeRequired` - Whether the user must change the password at next sign-in, because an administrator set it.

## List users

```
GET /api/users?offset=0&limit=25
```

Returns users sorted by username, including deactivated users, and the total number of users. Requires `users:read` and an administrator. `limit` is capped at 100.

```json
{
  "users": [ { "username": "ci", "email": "ci@example.com", "role": "user", "active": true, "created": "2026-10-05T14:03:11.000Z", "deactivatedAt": null, "passwordSet": false, "passwordChangeRequired": false } ],
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
* `password` (optional) - See [password rules](#password-rules). The user must change it at next sign-in, because an administrator chose it. Without one, the user can only use API keys.

`201 Created`:

```json
{
  "username": "ci",
  "role": "user"
}
```

| Status | Meaning |
|--------|---------|
| 400 | The username is missing or is `me`, the role is not `user` or `admin`, or the password breaks the [password rules](#password-rules). |
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

## Passwords

### Password rules

A password must be at least 16 characters and at most 72 bytes in UTF-8, the most bcrypt reads. A password outside those limits is refused with `400 Bad Request`. Passwords are stored as bcrypt hashes.

Setting, changing, or resetting a password revokes the user's session keys, the keys issued when a person signs in. Long-lived API keys are not affected.

### Change your own password

```
PUT /api/users/me/password
```

```json
{
  "currentPassword": "the-current-password",
  "newPassword": "a-new-password-of-16-or-more"
}
```

Changes the password of the calling key's own user. The current password is required, and the new one must differ from it. Clears a required change. Requires `users:write`; does not require an administrator. The calling key is revoked too if it is a session key.

| Status | Meaning |
|--------|---------|
| 204 | The password was changed. |
| 400 | A field is missing, the new password breaks the [password rules](#password-rules), or it is the same as the current one. |
| 403 | The current password is not correct, or the key does not hold `users:write`. |
| 409 | The user has no password, so an administrator sets the first one, or another request changed the password at the same time. |

### Set or reset a user's password

```
PUT /api/users/{username}/password
```

```json
{
  "password": "a-password-of-16-or-more"
}
```

Sets another user's password without the current one, and marks it as one the user must change at next sign-in. Requires `users:write` and an administrator.

On the calling administrator's own user, it sets only the first password, with no change required. This is how the `admin` user gets a password: call it with the [bootstrap API key](../../account/api_keys.md#bootstrapping-an-api-key-for-automation). Once the user has a password, change it with `PUT /api/users/me/password`, which requires the current one, so a stolen key cannot replace its own user's password unnoticed.

```
curl -k -X PUT "https://localhost:8080/api/users/admin/password" \
  -H "Authorization: Bearer $PHILTER_BOOTSTRAP_API_KEY" \
  -H "Content-Type: application/json" \
  --data '{"password":"a-password-of-16-or-more"}'
```

| Status | Meaning |
|--------|---------|
| 204 | The password was set. |
| 400 | The password is missing or breaks the [password rules](#password-rules). |
| 404 | There is no user with that username. |
| 409 | The user is the caller and already has a password, or another request changed the password at the same time. |

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
| `user_password_set` | A user without a password was given one, at creation or with `PUT /api/users/{username}/password`. The principal is the calling administrator, the associated object is the user, and the details name the calling API key and whether a change is required. |
| `user_password_reset` | An administrator replaced a user's password. Recorded like `user_password_set`. |
| `user_password_changed` | A user changed their own password. The principal and associated object are the user, and the details name the calling API key. |
| `api_key_deleted` | A session key was revoked because the password was set, changed, or reset. The details give the reason. |

No event records a password or its hash. These are security events, so they cannot be switched off. They are readable through [`GET /api/audit`](audit_api.md). See [Auditing](../../auditing.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [API Keys API](api_keys_api.md)
* [Audit Log API](audit_api.md)
