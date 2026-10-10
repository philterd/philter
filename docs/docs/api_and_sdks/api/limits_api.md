# Limits API

The Limits API returns the limits and rules Philter enforces, and what the caller may do. A client such as a user interface reads them here instead of copying them, so it checks input against the values Philter actually applies, including any changed through an environment variable, and shows or hides features to match the caller.

A sign-in page, before anyone has signed in, reads whether password sign-in is available and the password rules from [`GET /api/sign-in`](sign_in_api.md#get-the-sign-in-options) instead.

## Get the limits

```
GET /api/limits
```

Any key can call it, whatever its scopes, because it describes the API and the caller's own access. That includes a [session key](../../account/api_keys.md#session-keys) that must first change its password or enroll in MFA, so a page asking for a new password can show the password rules.

```
curl -k "https://localhost:8080/api/limits" \
  -H "Authorization: Bearer <api key>"
```

```json
{
  "requests": {
    "maxDocumentBytes": 10485760,
    "maxBodyBytes": 262144,
    "defaultPageSize": 25,
    "maxPageSize": 100
  },
  "password": {
    "minCharacters": 16,
    "maxBytes": 72
  },
  "names": {
    "forbiddenCharacters": ["/", "\\", ";", "%"],
    "controlCharactersForbidden": true,
    "reservedNames": [".", ".."],
    "rule": "cannot contain /, \\, ;, %, or control characters, and cannot be . or .."
  },
  "policies": {
    "nameMaxLength": 50,
    "namePattern": "^[a-zA-Z0-9_-]+$",
    "reservedNamePrefix": "managed_",
    "reservedNames": ["templates"],
    "defaultPolicyName": "default",
    "descriptionMaxLength": 200,
    "notesMaxLength": 1000
  },
  "customLists": {
    "maxItems": 100,
    "itemMaxLength": 50
  },
  "redactLists": {
    "maxTerms": 1000,
    "termMaxLength": 100
  },
  "contexts": {
    "maxPerUser": 10,
    "maxEntries": 10000
  },
  "webhook": {
    "secretMinLength": 16
  },
  "legalHolds": {
    "scopeTypes": ["document_chain", "user"]
  },
  "users": {
    "roles": ["admin", "user"],
    "reservedUsernames": ["me"]
  },
  "auditExport": {
    "maxWindowDays": 30,
    "defaultPageSize": 100,
    "maxPageSize": 1000
  },
  "sessionKeys": {
    "idleTimeoutMinutes": 30,
    "maxLifetimeMinutes": 720
  },
  "caller": {
    "role": "admin",
    "contextCount": 3,
    "crossUserAccess": false,
    "ledgerDeletion": false
  }
}
```

The values above are the defaults. Each one in the response is the value the running Philter enforces.

### Requests

* `requests.maxDocumentBytes` - The largest body [`POST /api/filter`](filtering_api.md) and `POST /api/explain` accept, in bytes. Set by [`MAX_FILE_SIZE_BYTES`](../../settings.md#redaction-engine).
* `requests.maxBodyBytes` - The largest body any other `POST` or `PUT` accepts, in bytes. Set by [`MAX_FILE_SIZE_BYTES_OTHER`](../../settings.md#redaction-engine).
* `requests.defaultPageSize` - The page size a listing uses when `limit` is not given.
* `requests.maxPageSize` - The largest `limit` a listing honors. A larger `limit` is reduced to this.

### Names and passwords

* `password.minCharacters` and `password.maxBytes` - The fewest characters and the most UTF-8 bytes a password can have. See [Passwords](users_api.md#passwords).
* `names` - The rule for names used in a request path: [custom list](custom_lists_api.md) names, [context](contexts_api.md) names, [legal hold](legal_holds_api.md) references, and usernames. Such a name cannot contain any of `forbiddenCharacters`, or a control character when `controlCharactersForbidden` is `true`, and cannot be one of `reservedNames`. `rule` states the same rule in words.
* `users.reservedUsernames` - Usernames that cannot be used, compared without regard to case.
* `users.roles` - The roles a user can have.

### Resources

* `policies` - A [policy](policies_api.md) name can have up to `nameMaxLength` characters, must match `namePattern`, cannot start with `reservedNamePrefix`, which is reserved for managed policies, and cannot be one of `reservedNames`, which the API's paths use for something else. `defaultPolicyName` is the policy every user is given, which cannot be deleted. A description can have up to `descriptionMaxLength` characters and notes up to `notesMaxLength`.
* `customLists` - A [custom list](custom_lists_api.md) can have up to `maxItems` items of up to `itemMaxLength` characters each.
* `redactLists` - The [always-redact and never-redact lists](redact_lists_api.md) can each have up to `maxTerms` terms of up to `termMaxLength` characters each.
* `contexts.maxPerUser` - The most [contexts](contexts_api.md) a user can have. Compare it with `caller.contextCount` to know whether the caller can create another.
* `contexts.maxEntries` - The most entries a context holds. Adding one more evicts the least-read entry. Set by [`MAX_CONTEXT_SIZE`](../../settings.md#contexts-and-disambiguation).
* `webhook.secretMinLength` - The fewest characters a [webhook](webhooks.md) secret can have.
* `legalHolds.scopeTypes` - The scope types a [legal hold](legal_holds_api.md) can have.
* `auditExport` - An [audit log export](audit_api.md) can span at most `maxWindowDays` days between its `from` and `to` dates. A page has `defaultPageSize` rows when `limit` is not given, and at most `maxPageSize`.

### Session keys

* `sessionKeys.idleTimeoutMinutes` - Minutes without a request after which a [session key](../../account/api_keys.md#session-keys) expires. Set by [`SESSION_KEY_IDLE_TIMEOUT_MINUTES`](../../settings.md#api-access).
* `sessionKeys.maxLifetimeMinutes` - Minutes after issue at which a session key expires, whatever its use. Set by [`SESSION_KEY_MAX_LIFETIME_MINUTES`](../../settings.md#api-access).

### The caller

* `caller.role` - The caller's role, read when the request is made, so a role changed since sign-in shows here.
* `caller.contextCount` - How many contexts the caller has.
* `caller.crossUserAccess` - Whether the caller may reach other users' resources with `owner` and `all_users`: `true` for an administrator when `ADMIN_CROSS_USER_ACCESS_ENABLED` is on.
* `caller.ledgerDeletion` - Whether the caller may [delete and purge](ledger_api.md) ledger chains: `true` for an administrator when `LEDGER_DELETION_ENABLED` is on.

For a caller who is not an administrator, `crossUserAccess` and `ledgerDeletion` are always `false`, so the response does not reveal how those settings are configured.

| Status | Meaning |
|--------|---------|
| 200 | The limits and the caller's capabilities. |
| 401 | The `Authorization` header is absent or the API key is not recognized. |

## See also

* [Endpoint inventory](endpoint_inventory.md)
* [Sign-in API](sign_in_api.md)
* [API Keys and Authentication](../../account/api_keys.md)
