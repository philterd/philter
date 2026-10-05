# Sign-in API

Password sign-in lets a person use Philter through a user interface, such as [Philter UI](https://github.com/philterd/philter-ui) (in development), without the interface holding an API key of its own. The interface sends the person's username and password, and Philter returns a [session key](../../account/api_keys.md#session-keys) for that user, which the interface uses for the person's requests until it expires or they sign out.

Password sign-in is **disabled by default**. Enable it with [`PASSWORD_SIGN_IN_ENABLED=true`](../../settings.md#api-access). While it is disabled, the endpoint returns `404 Not Found`, so a deployment that runs no user interface exposes no login endpoint. It is an environment variable rather than an admin setting, so an administrator's API key cannot turn it on.

A user can only sign in once they have a password; see [Passwords](users_api.md#passwords). Users without one, such as automation, use long-lived API keys.

## Sign in

```
POST /api/sign-in
```

```json
{
  "username": "jordan",
  "password": "the-users-password"
}
```

Requires no API key. `200 OK`:

```json
{
  "apiKey": "sk_AbCdEfGhIjKlMnOpQrStUvWxYz012345",
  "username": "jordan",
  "scopes": ["redact", "contexts:read", "..."],
  "expiresAt": "2026-10-06T02:03:11.000Z",
  "idleExpiresAt": "2026-10-05T14:33:11.000Z",
  "passwordChangeRequired": false
}
```

* `apiKey` - The session key. Send it as `Authorization: Bearer <apiKey>`. It is returned here and nowhere else.
* `scopes` - Every scope. The user's role still decides administrator access, so a user who is not an administrator cannot reach administrator endpoints.
* `expiresAt` - When the key's maximum lifetime ends ([`SESSION_KEY_MAX_LIFETIME_MINUTES`](../../settings.md#api-access)).
* `idleExpiresAt` - When the key expires unless it is used first ([`SESSION_KEY_IDLE_TIMEOUT_MINUTES`](../../settings.md#api-access)). Each request moves it forward.
* `passwordChangeRequired` - `true` when an administrator set the password. The key can then only change the password with [`PUT /api/users/me/password`](users_api.md#change-your-own-password) and [sign out](api_keys_api.md#sign-out); any other request is refused with `403 Forbidden`. Changing the password revokes the key, and the person signs in again with the new password.

A session key cannot create API keys, so signing in cannot produce a long-lived credential that outlives the session or a password reset. It can list, re-scope, and revoke keys like any other key.

```
curl -k -X POST "https://localhost:8080/api/sign-in" \
  -H "Content-Type: application/json" \
  --data '{"username":"jordan","password":"the-users-password"}'
```

| Status | Meaning |
|--------|---------|
| 200 | Signed in. |
| 401 | The username or password is not valid. |
| 404 | Password sign-in is not enabled. |

A wrong password, an unknown username, a user without a password, and a deactivated user all get the same `401` response, and take about as long, so the endpoint does not reveal which usernames exist. Sign-in does not yet lock a username after repeated failures; put the endpoint behind rate limiting at your load balancer if it is reachable from untrusted networks.

To sign out, call [`DELETE /api/api-keys/current`](api_keys_api.md#sign-out) with the session key.

## Auditing

| Event | Recorded when |
|-------|---------------|
| `sign_in_succeeded` | A person signed in. The principal is the user, the associated object is the session key, and the details name the username and the key. |
| `sign_in_failed` | A sign-in was refused. The details name the username that was tried, cut to 100 characters, with commas and control characters replaced by `_`. |
| `api_key_created` | The session key was issued, with `session: true` in the details. |

Both sign-in events record the client IP address. No event records the password. These are security events, so they cannot be switched off. See [Auditing](../../auditing.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [Users API](users_api.md)
* [API Keys API](api_keys_api.md)
