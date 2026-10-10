# Users API

These endpoints create and manage users. They are how a deployment is administered: by automation such as CI, a marketplace image, or an infrastructure-as-code run, or by a separate user interface, such as [Philter UI](https://github.com/philterd/philter-ui) (in development).

Requests authenticate with [API keys](../../account/api_keys.md). Create and manage a user's keys with the [API Keys API](api_keys_api.md). A user can also have a password, for a person who [signs in](sign_in_api.md) through a user interface. A user without a password can only use API keys. No endpoint returns a password or its hash. See [Passwords](#passwords).

Every endpoint requires an administrator in addition to its [scope](../../account/api_keys.md#scopes), except `GET /api/users/me`, which any key holding `users:read` can call, and `PUT /api/users/me/password` and the `/api/users/me/mfa` endpoints, which any key holding `users:write` can call. A request that lacks the scope and a request from a non-administrator are both refused with `403 Forbidden`; the message says which.

There is no setting that turns these endpoints off. A headless deployment is administered through them, so an administrator's API key, or an administrator's [session key](../../account/api_keys.md#session-keys) from [sign-in](sign_in_api.md), is the credential that manages it, and they are guarded like every other administrator endpoint: by the administrator role and a scope. Limit which keys hold `users:write` and `api-keys:write`.

The first administrator key comes from [`PHILTER_BOOTSTRAP_API_KEY`](../../account/api_keys.md#bootstrapping-an-api-key-for-automation). These endpoints are how that key provisions the rest.

## The user object

```json
{
  "id": "6a0f1c2e9b1d4e3f2a1b0c9d",
  "username": "ci",
  "email": "ci@example.com",
  "role": "user",
  "active": true,
  "created": "2026-10-05T14:03:11.000Z",
  "deactivatedAt": null,
  "passwordSet": false,
  "passwordChangeRequired": false,
  "mfaEnabled": false,
  "mfaLocked": false
}
```

* `id` - The user's id. Audit events name their actor by this id, as `apiKeyId`, alongside its `username`.
* `role` - `user` or `admin`.
* `active` - `false` once the user is deactivated. A deactivated user's API keys are rejected.
* `deactivatedAt` - When the user was deactivated, or `null`.
* `passwordSet` - Whether the user has a password.
* `passwordChangeRequired` - Whether the user must change the password at next sign-in, because an administrator set it.
* `mfaEnabled` - Whether the user is enrolled in [MFA](#multi-factor-authentication). The secret is never returned.
* `mfaLocked` - Whether the user's MFA is locked after repeated bad codes, until an administrator unlocks it.

## List users

```
GET /api/users?offset=0&limit=25
```

Returns users sorted by username, including deactivated users, and the total number of users. Requires `users:read` and an administrator. `limit` is capped at 100.

```json
{
  "users": [ { "id": "6a0f1c2e9b1d4e3f2a1b0c9d", "username": "ci", "email": "ci@example.com", "role": "user", "active": true, "created": "2026-10-05T14:03:11.000Z", "deactivatedAt": null, "passwordSet": false, "passwordChangeRequired": false, "mfaEnabled": false, "mfaLocked": false } ],
  "total": 1
}
```

## Get the calling key's user

```
GET /api/users/me
```

Returns the user that owns the API key making the request. Requires `users:read`; does not require an administrator. `me` is reserved and cannot be used as a username.

The user object has two more fields here, about the deployment, which a user who is not an administrator cannot read from the [Settings API](settings_api.md):

* `mfaAvailable` - Whether users may [enroll in MFA](#start-enrollment).
* `mfaRequired` - Whether every user who signs in must enroll. Never `true` while `mfaAvailable` is `false`.

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

* `username` (required) - Must not already belong to a user, including a deactivated one holding the name in reserve, and must not be `me`. Because the username is how the user is addressed in a request path, it cannot contain `/`, `\`, `;`, `%`, or control characters, and cannot be `.` or `..`. Other text, including an email address, is allowed.
* `email` (optional) - The user's email address.
* `role` (optional) - `user` (the default) or `admin`.
* `password` (optional) - See [password rules](#password-rules). The user must change it at next sign-in, because an administrator chose it. Without one, the user can only use API keys.

`201 Created`:

```json
{
  "id": "6a0f1c2e9b1d4e3f2a1b0c9d",
  "username": "ci",
  "role": "user"
}
```

| Status | Meaning |
|--------|---------|
| 400 | The username is missing, is `me`, or cannot be used in a request path, the role is not `user` or `admin`, or the password breaks the [password rules](#password-rules). |
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

Deactivates the user and returns it. The user's API keys stop working at once. The user and all of its data (API keys, policies, contexts, lists, and redaction ledger) are retained, so the user can be reactivated. While cross-user access is enabled, an administrator can still read and manage that data by naming the user as `owner`, for example to release a legal hold, without reactivating the user. Requires `users:write` and an administrator.

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

## Multi-factor authentication

Users who [sign in](sign_in_api.md) with a password can add TOTP multi-factor authentication, with an authenticator app such as Google Authenticator, Authy, or 1Password. Once enrolled, sign-in asks for a code from the app as well as the password. MFA is available only when an administrator turns on `mfaAvailable` in the [settings](settings_api.md); `mfaRequired` makes every user who signs in enroll. A user who is already enrolled is always asked for a code, even if `mfaAvailable` is turned off later.

The secret is encrypted at rest with `PHILTER_ENCRYPTION_KEY` and returned only when enrollment starts. A code is six digits and changes every 30 seconds; the codes for the 30 seconds either side of now are also accepted, to allow for clock differences. Each code is accepted once. After five consecutive bad codes the user is locked: no code is accepted until an administrator unlocks them or removes the enrollment. A good code resets the count.

### Start enrollment

```
POST /api/users/me/mfa
```

Generates a secret for the calling key's own user. Requires `users:write`; does not require an administrator.

`200 OK`:

```json
{
  "secret": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
  "otpauthUri": "otpauth://totp/Philter:jordan?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Philter&algorithm=SHA1&digits=6&period=30"
}
```

Show `otpauthUri` as a QR code for the app to scan, or have the person type in `secret`. Neither is returned again. Enrollment does not apply until it is confirmed; starting again replaces an unconfirmed secret.

| Status | Meaning |
|--------|---------|
| 409 | MFA is not available on this deployment, or the user is already enrolled. |

### Confirm enrollment

```
POST /api/users/me/mfa/confirm
```

```json
{
  "code": "123456"
}
```

Completes enrollment with a code from the app, and returns `204 No Content`. Revokes the user's session keys, so the person signs in again, this time with a code. Requires `users:write`.

| Status | Meaning |
|--------|---------|
| 400 | The code is missing or not valid. |
| 409 | MFA is not available, the user is already enrolled, or no enrollment was started. |

### Remove your own enrollment

```
POST /api/users/me/mfa/remove
```

```json
{
  "code": "123456"
}
```

Removes the calling key's own user's MFA and returns `204 No Content`. It takes a valid code, so a stolen key cannot turn MFA off, and a bad code counts toward the lock. A person who has lost their device asks an administrator. Requires `users:write`.

| Status | Meaning |
|--------|---------|
| 400 | The code is missing. |
| 403 | The code is not valid, or the user's MFA is locked. |
| 409 | The user is not enrolled. |

### Remove a user's enrollment

```
DELETE /api/users/{username}/mfa
```

Removes another user's MFA, for a person who has lost their device, clears any lock, and returns `204 No Content`. An administrator removes their own with `POST /api/users/me/mfa/remove`. Requires `users:write` and an administrator.

| Status | Meaning |
|--------|---------|
| 404 | There is no user with that username. |
| 409 | The user is the caller, or is not enrolled. |

### Unlock a user

```
POST /api/users/{username}/mfa/unlock
```

Unlocks a user locked after five bad codes, resets the count, and returns `204 No Content`. Requires `users:write` and an administrator.

| Status | Meaning |
|--------|---------|
| 404 | There is no user with that username. |
| 409 | The user is not locked. |

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
| `user_mfa_enrolled` | A user confirmed MFA enrollment. The principal and associated object are the user, and the details name the calling API key. |
| `user_mfa_removed` | A user's MFA was removed, by the user with a code or by an administrator. The principal is whoever removed it, and the details name the calling API key. |
| `user_mfa_locked` | A user's MFA was locked after five consecutive bad codes. Recorded once per lock. |
| `user_mfa_unlocked` | An administrator unlocked a user's MFA. The principal is the administrator, and the details name the calling API key. |
| `api_key_deleted` | A session key was revoked because the password was set, changed, or reset, or MFA was enrolled. The details give the reason. |

No event records a password, its hash, or an MFA secret or code. These are security events, so they cannot be switched off. They are readable through [`GET /api/audit`](audit_api.md). See [Auditing](../../auditing.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [API Keys API](api_keys_api.md)
* [Audit Log API](audit_api.md)
