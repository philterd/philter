# Sign-in API

Password sign-in lets a person use Philter through a user interface, such as [Philter UI](https://github.com/philterd/philter-ui) (in development), without the interface holding an API key of its own. The interface sends the person's username and password, and Philter returns a [session key](../../account/api_keys.md#session-keys) for that user, which the interface uses for the person's requests until it expires or they sign out. For how sign-in is protected as a whole, see [Sign-in Security](../../sign_in_security.md).

Password sign-in is **disabled by default**. Enable it with [`PASSWORD_SIGN_IN_ENABLED=true`](../../settings.md#api-access). While it is disabled, every sign-in endpoint returns `404 Not Found`, so a deployment that runs no user interface exposes no login endpoint. It is an environment variable rather than an admin setting, so an administrator's API key cannot turn it on.

A user can only sign in once they have a password; see [Passwords](users_api.md#passwords). Users without one, such as automation, use long-lived API keys.

## Get the sign-in options

```
GET /api/sign-in
```

Tells a sign-in page, before anyone has signed in, whether password sign-in is available, and gives the rules a password must meet, for a page where a person sets one. Requires no API key. `200 OK` means password sign-in is enabled:

```json
{
  "password": {
    "minCharacters": 16,
    "maxBytes": 72
  }
}
```

* `password.minCharacters` - The fewest characters a password can have.
* `password.maxBytes` - The most bytes a password can have in UTF-8.

| Status | Meaning |
|--------|---------|
| 200 | Password sign-in is enabled. |
| 404 | Password sign-in is not enabled. |

Once signed in, a client reads the rest of Philter's limits from [`GET /api/limits`](limits_api.md).

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

Requires no API key. For a user enrolled in [MFA](users_api.md#multi-factor-authentication), the response is a challenge instead of a key; see [Complete sign-in with a code](#complete-sign-in-with-a-code). Otherwise, `200 OK`:

```json
{
  "id": "6a0f1c2e9b1d4e3f2a1b0c9d",
  "apiKey": "sk_AbCdEfGhIjKlMnOpQrStUvWxYz012345",
  "username": "jordan",
  "scopes": ["redact", "contexts:read", "..."],
  "expiresAt": "2026-10-06T02:03:11.000Z",
  "idleExpiresAt": "2026-10-05T14:33:11.000Z",
  "passwordChangeRequired": false,
  "mfaEnrollmentRequired": false
}
```

* `id` - The session key's id, as [`GET /api/api-keys`](api_keys_api.md#list-your-keys) lists it, so a client can recognize its own key among the user's keys.
* `apiKey` - The session key. Send it as `Authorization: Bearer <apiKey>`. It is returned here and nowhere else.
* `scopes` - Every scope. The user's role still decides administrator access, so a user who is not an administrator cannot reach administrator endpoints.
* `expiresAt` - When the key's maximum lifetime ends ([`SESSION_KEY_MAX_LIFETIME_MINUTES`](../../settings.md#api-access)).
* `idleExpiresAt` - When the key expires unless it is used first ([`SESSION_KEY_IDLE_TIMEOUT_MINUTES`](../../settings.md#api-access)). Each request moves it forward.
* `passwordChangeRequired` - `true` when an administrator set the password. The key can then only change the password with [`PUT /api/users/me/password`](users_api.md#change-your-own-password), read the [limits](limits_api.md), and [sign out](api_keys_api.md#sign-out); any other request is refused with `403 Forbidden`. Changing the password revokes the key, and the person signs in again with the new password.
* `mfaEnrollmentRequired` - `true` when the `mfaRequired` [setting](settings_api.md) is on and the user is not enrolled in MFA. The key can then only [enroll](users_api.md#start-enrollment), read the [limits](limits_api.md), and sign out. Confirming enrollment revokes the key, and the person signs in again, with a code.

A session key cannot create API keys, and can narrow another key's scopes but not widen them, so signing in cannot produce a long-lived credential that outlives the session or a password reset. It can list and revoke keys like any other key.

```
curl -k -X POST "https://localhost:8080/api/sign-in" \
  -H "Content-Type: application/json" \
  --data '{"username":"jordan","password":"the-users-password"}'
```

| Status | Meaning |
|--------|---------|
| 200 | Signed in. |
| 401 | The username or password is not valid. |
| 403 | The password is right, but the user's MFA is locked after repeated bad codes. An administrator must unlock it. |
| 404 | Password sign-in is not enabled. |
| 429 | The username is locked after repeated failures, or the client address is over the rate limit. `Retry-After` gives the most seconds to wait, and `reason` says which; see [Lockout and rate limiting](#lockout-and-rate-limiting). |

A wrong password, an unknown username, a user without a password, and a deactivated user all get the same `401` response, and take about as long, so the endpoint does not reveal which usernames exist. Repeated failures lock the username, and requests are rate-limited per client address; see [Lockout and rate limiting](#lockout-and-rate-limiting).

To sign out, call [`DELETE /api/api-keys/current`](api_keys_api.md#sign-out) with the session key.

## Complete sign-in with a code

For a user enrolled in MFA, `POST /api/sign-in` returns a challenge and no key:

```json
{
  "mfaRequired": true,
  "challenge": "y4pY0m3l8f2rVq7d...",
  "challengeExpiresAt": "2026-10-05T14:08:11.000Z"
}
```

Send the challenge with a code from the person's authenticator app:

```
POST /api/sign-in/mfa
```

```json
{
  "challenge": "y4pY0m3l8f2rVq7d...",
  "code": "123456"
}
```

Requires no API key. A valid code returns the same `200 OK` response as a sign-in without MFA, including the session key. A key is never issued on the password alone.

A challenge expires after five minutes and is used up by any attempt, right or wrong, so a wrong code means signing in again with the password. Each code is accepted once. The fifth consecutive bad code locks the user until an administrator [unlocks](users_api.md#unlock-a-user) them.

| Status | Meaning |
|--------|---------|
| 200 | Signed in. |
| 401 | The challenge is unknown, used, or expired, or the code is not valid. The response does not say which. |
| 403 | The user's MFA is locked. |
| 404 | Password sign-in is not enabled. |
| 429 | The client address is over the rate limit. `reason` is `rate_limited`. |

## Lockout and rate limiting

Two limits protect sign-in against password guessing:

* **Username lockout.** After [`SIGN_IN_MAX_FAILURES`](../../settings.md#api-access) (default 5) failed sign-ins for a username within [`SIGN_IN_LOCKOUT_MINUTES`](../../settings.md#api-access) (default 15), the username is locked for that many minutes. While it is locked, sign-in is refused with `429 Too Many Requests` and `Retry-After` set to the lockout period, before the password is checked, even if it is right, and those attempts are not counted, so the lock ends on time. Attempts are counted before their passwords are checked, so a burst of parallel guesses gets no more checks than the limit. The lock then clears on its own. A sign-in with the right password resets the count. A username that does not exist locks the same way, so a lock says nothing about which usernames exist.
* **Rate limiting.** Each client address may make [`SIGN_IN_RATE_LIMIT_PER_MINUTE`](../../settings.md#api-access) (default 20) sign-in requests a minute, counting both `POST /api/sign-in` and `POST /api/sign-in/mfa`. Further requests in the minute are refused with `429 Too Many Requests` and `Retry-After: 60`. The address is the one the [audit log](../../auditing.md) records: the connection's, or, for a request from a [trusted proxy](../../settings.md#api-access), the client address its `X-Forwarded-For` header names. `TRUSTED_PROXIES` defaults to the private and loopback ranges, so if clients reach Philter directly from a private network, set it to your actual proxies; otherwise those clients can choose the address they are counted under. The username lockout does not depend on the address.

Both refusals have the same status and header, so the body's `reason` tells them apart: `locked` for a username lockout and `rate_limited` for the per-address limit. Use `reason` rather than the message, which is written for people and may change.

```json
{
  "message": "Too many failed sign-ins for this username. Try again later.",
  "reason": "locked"
}
```

Both are counted in the cache. With a shared [Valkey/Redis cache](../../caching.md), the counts are shared across every instance. With the default in-memory cache they are **per instance**, so behind a load balancer that spreads requests across instances, an attacker gets each instance's allowance and can evade them. Use a shared cache for any deployment with more than one instance. If the in-memory cache is full, sign-in is refused until counters can be stored again, rather than letting attempts go uncounted.

Codes at the MFA step have their own lock, which needs an administrator to clear; see [Multi-factor authentication](users_api.md#multi-factor-authentication).

## Auditing

| Event | Recorded when |
|-------|---------------|
| `sign_in_succeeded` | A person signed in. The principal is the user, the associated object is the session key, and the details name the username and the key. |
| `sign_in_failed` | A sign-in was refused. The details name the username that was tried, cut to 100 characters, with commas and control characters replaced by `_`, or, at the MFA step, the user and the reason: an invalid code, a locked user, or an unknown, used, or expired challenge. |
| `user_mfa_locked` | The fifth consecutive bad code locked the user. |
| `sign_in_locked` | Repeated failures locked a username. The details name the username, the limit, and the lockout period. Recorded once per lock. |
| `sign_in_rate_limited` | A client address went over the rate limit. Recorded for the first refused request in each minute, not every one. |
| `api_key_created` | The session key was issued, with `session: true` in the details. |

Both sign-in events record the client IP address. No event records the password or an MFA code. These are security events, so they cannot be switched off. See [Auditing](../../auditing.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [Users API](users_api.md)
* [API Keys API](api_keys_api.md)
* [Limits API](limits_api.md)
