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
  "bootstrap": false
}
```

* `id` - Used to change the key's scopes or revoke it.
* `prefix` - The first characters of the key, to tell keys apart. The key itself is never returned after it is created.
* `bootstrap` - `true` for the key seeded from [`PHILTER_BOOTSTRAP_API_KEY`](../../account/api_keys.md#bootstrapping-an-api-key-for-automation).

Philter does not record when a key was last used.

## List your keys

```
GET /api/api-keys?offset=0&limit=25
```

Lists the active keys belonging to the calling key's user, oldest first, and the total. Requires `api-keys:read`. `limit` is capped at 100.

```json
{
  "apiKeys": [ { "id": "6a0f1c2e9b1d4e3f2a1b0c9d", "prefix": "sk_AbCdEfGhI...", "scopes": ["redact"], "created": "2026-10-05T14:03:11.000Z", "bootstrap": false } ],
  "total": 1
}
```

## List a user's keys

```
GET /api/users/{username}/api-keys?offset=0&limit=25
```

Lists the named user's active keys. Requires `api-keys:read` and an administrator. Returns `404 Not Found` if there is no such user.

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
| 403 | The key does not hold `api-keys:write`, the caller is not an administrator (second form), or a requested scope is not held by the calling key. The message says which. |

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
| 403 | The key does not hold `api-keys:write`, a requested scope is not held by the calling key, or the key being changed holds a scope the calling key does not. |
| 404 | There is no active key with that ID that the caller may manage. A non-administrator gets this for another user's key. |

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

### When revocation and scope changes take effect

Philter caches each resolved key for up to [`API_KEY_CACHE_TTL_SECONDS`](../../caching.md) (default 60). Revoking a key or changing its scopes evicts it from the cache, so the change applies to the next request on the instance that handled it, and on every instance that shares the same [Valkey/Redis cache](../../caching.md). Where instances do not share a cache, or a request on another instance races the change, another instance can accept the old key or scopes until its cache entry expires, at most `API_KEY_CACHE_TTL_SECONDS` later.

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
| `api_key_deleted` | A key was revoked. The details name the calling user and API key. |

These are security events, so they cannot be switched off. They are readable through [`GET /api/audit`](audit_api.md).

## See also

* [API Keys and Authentication](../../account/api_keys.md)
* [Users API](users_api.md)
