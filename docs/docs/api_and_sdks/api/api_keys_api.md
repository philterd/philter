# API Keys API

These endpoints list, create, re-scope, and revoke [API keys](../../account/api_keys.md). A caller manages its own user's keys. An administrator can also manage any other user's keys.

A key is bounded by the key calling the endpoint:

* It cannot grant a scope it does not hold, whether creating a key or changing one.
* It cannot change or revoke a key that holds a scope it does not hold.
* It cannot revoke itself. Revoke a key with another key.

## The key object

```json
{
  "id": "6a0f1c2e9b1d4e3f2a1b0c9d",
  "prefix": "sk_AbCdEfGhI...",
  "scopes": ["redact", "policies:read"],
  "created": "2026-10-05T14:03:11.000Z",
  "bootstrap": false,
  "session": false,
  "expiresAt": null,
  "idleExpiresAt": null,
  "lastUsedAt": null
}
```

* `id` - Used to change the key's scopes or revoke it.
* `prefix` - The first characters of the key, to tell keys apart. The key itself is never returned after it is created.
* `bootstrap` - `true` for the key seeded from [`PHILTER_BOOTSTRAP_API_KEY`](../../account/api_keys.md#bootstrapping-an-api-key-for-automation).
* `session` - `true` for a [session key](../../account/api_keys.md#session-keys), issued when a person signs in; `false` for a long-lived key.
* `expiresAt` - Session keys only: when the key's maximum lifetime ends. `null` for a long-lived key.
* `idleExpiresAt` - Session keys only: when the key expires unless it is used before then. Each request moves it forward. `null` for a long-lived key.
* `lastUsedAt` - Session keys only: the last request made with the key. Philter does not record when a long-lived key was last used.

## List the scopes

```
GET /api/api-keys/scopes
```

Returns every scope an API key can carry, with what it allows, in the order Philter declares them. Use it to offer scopes to choose from instead of hard-coding them; a scope Philter adds appears here. Any key can call it, whatever its scopes. See [Scopes](../../account/api_keys.md#scopes).

```json
{
  "scopes": [
    { "name": "redact", "description": "Redact text and documents, and explain redactions." },
    { "name": "contexts:read", "description": "List and read contexts and their entries, including exports." }
  ]
}
```

## List your keys

```
GET /api/api-keys?offset=0&limit=25
```

Lists the active keys belonging to the calling key's user, oldest first, and the total. Requires `api-keys:read`. `limit` is capped at 100.

* `session` (optional) - `true` lists only [session keys](../../account/api_keys.md#session-keys), `false` only long-lived keys. Left out, both are listed. `total` counts only the keys listed, so paging works the same either way.
* `order` (optional, default `asc`) - `asc` lists the oldest key first, `desc` the newest. `sort` accepts only `created`; see [Listings](../api.md#listings).

```json
{
  "apiKeys": [
    {
      "id": "6a0f1c2e9b1d4e3f2a1b0c9d",
      "prefix": "sk_AbCdEfGhI...",
      "scopes": ["redact"],
      "created": "2026-10-05T14:03:11.000Z",
      "bootstrap": false,
      "session": false,
      "expiresAt": null,
      "idleExpiresAt": null,
      "lastUsedAt": null
    }
  ],
  "total": 1
}
```

## List a user's keys

```
GET /api/users/{username}/api-keys?offset=0&limit=25
```

Lists the named user's active keys. Accepts `session` as [List your keys](#list-your-keys) does. Requires `api-keys:read` and an administrator. Returns `404 Not Found` if there is no such user.

## Create a key

```
POST /api/api-keys
POST /api/users/{username}/api-keys
```

The first form creates a key for the calling key's user and does not require an administrator, so a key can be rotated by the user that owns it. The second creates a key for the named user and requires an administrator; it returns `404 Not Found` if there is no active user with that username. Both require `api-keys:write`.

```json
{
  "scopes": ["redact"]
}
```

* `scopes` (required) - At least one [scope](../../account/api_keys.md#scopes), each held by the calling key. There is no default.

`201 Created`:

```json
{
  "id": "6a0f1c2e9b1d4e3f2a1b0c9d",
  "username": "ci",
  "apiKey": "sk_abcdefghijklmnopqrstuvwxyz012345",
  "scopes": ["redact"]
}
```

The key value is returned once, in this response. Philter stores only its SHA-256 hash and cannot recover it.

| Status | Meaning |
|--------|---------|
| 400 | No scopes were given, or one of them is not a scope. |
| 403 | The key does not hold `api-keys:write`, is a [session key](../../account/api_keys.md#session-keys), the caller is not an administrator (second form), or a requested scope is not held by the calling key. The message says which. A session key cannot create keys, so a session cannot produce a credential that outlives it. |

```
curl -k "https://localhost:8080/api/api-keys" \
  -H "Authorization: Bearer sk_abcdefghijklmnopqrstuvwxyz012345" \
  -H "Content-Type: application/json" \
  --data '{"scopes":["redact"]}'
```

To rotate a key: create a new key, update the integration to use it, then revoke the old key with the new one.

## Change a key's scopes

```
PUT /api/api-keys/{keyId}/scopes
```

```json
{
  "scopes": ["redact"]
}
```

Replaces the key's scopes and returns the key. The key value does not change, so integrations keep working with the same credential. Requires `api-keys:write`.

| Status | Meaning |
|--------|---------|
| 400 | No scopes were given, or one of them is not a scope. |
| 403 | The key does not hold `api-keys:write`, a requested scope is not held by the calling key, the key being changed holds a scope the calling key does not, or the calling key is a [session key](../../account/api_keys.md#session-keys) and the change adds a scope. A session key can narrow a key's scopes but not widen them. |
| 404 | There is no active key with that ID that the caller may manage. A non-administrator gets this for another user's key. |
| 409 | The key is the one making the request. A key cannot change its own scopes, even to narrow them, since a client doing so would cut its own access; change them with another key. |

## Revoke a key

```
DELETE /api/api-keys/{keyId}
```

Revokes the key and returns `204 No Content`. A revoked key cannot be restored. Requires `api-keys:write`.

| Status | Meaning |
|--------|---------|
| 403 | The key does not hold `api-keys:write`, or the key being revoked holds a scope the calling key does not. |
| 404 | There is no active key with that ID that the caller may manage. A non-administrator gets this for another user's key. |
| 409 | The key is the one making the request. |

## Sign out

```
DELETE /api/api-keys/current
```

Revokes the [session key](../../account/api_keys.md#session-keys) making the request and returns `204 No Content`. Any key can call it, whatever its scopes, because it can only end the caller's own access; no administrator is needed.

| Status | Meaning |
|--------|---------|
| 409 | The calling key is a long-lived key. A long-lived key cannot revoke itself; revoke it with another key. |

## Revoke a user's session keys

```
DELETE /api/users/{username}/session-keys
```

Revokes every session key the user holds, signing the person out everywhere. Long-lived keys are not affected. Requires `api-keys:write` and an administrator.

`200 OK`:

```json
{
  "revoked": 2
}
```

| Status | Meaning |
|--------|---------|
| 404 | There is no user with that username. |

### When revocation and scope changes take effect

Philter caches each resolved key for up to [`API_KEY_CACHE_TTL_SECONDS`](../../caching.md) (default 60). Revoking a key or changing its scopes evicts it from the cache, so the change applies to the next request on the instance that handled it, and on every instance that shares the same [Valkey/Redis cache](../../caching.md). Where instances do not share a cache, or a request on another instance races the change, another instance can accept the old key or scopes until its cache entry expires, at most `API_KEY_CACHE_TTL_SECONDS` later. Session keys are checked against the database on every request, so their revocation and expiry apply at once on every instance.

## Errors common to every endpoint

| Status | Meaning |
|--------|---------|
| 401 | The `Authorization` header is absent or the API key is not recognized. |
| 403 | The key does not hold the endpoint's scope, or the caller is not an administrator where one is required. The message says which. |

## Auditing

| Event | Recorded when |
|-------|---------------|
| `api_key_created` | A key was created. The principal is the new key, the associated object is the user it belongs to, and the details name the calling user and API key and the scopes. |
| `api_key_scopes_changed` | A key's scopes were changed. The details record the scopes before and after and name the calling user and API key. |
| `api_key_deleted` | A key was revoked. The details name the calling user and API key, or give the reason, such as `signed out` or a password change. |
| `api_key_expired` | A session key passed its idle timeout or maximum lifetime. The principal is the key, the associated object is its user, and the details give the reason. |

These are security events, so they cannot be switched off. They are readable through [`GET /api/audit`](audit_api.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [Users API](users_api.md)
