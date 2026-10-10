# Always/Never Redact Lists API

The Always/Never Redact Lists API provides endpoints for retrieving and replacing an account's always-redact and never-redact lists. These are the terms that are unconditionally redacted, or unconditionally preserved, across all of your redaction policies and contexts. See [Always/Never Redact Lists](../../redaction/redact_lists.md) for an overview of the feature.

The lists are a per-account singleton resource: there is always exactly one (possibly empty) pair of lists per account, so there is no create or delete, only get, replace (`POST`), and append (`PUT`). Each list can also be [read](#get-one-list) and [replaced](#replace-one-list) on its own, on the condition that nobody changed it since you read it, which is how a client that lets people edit the lists avoids overwriting someone else's change.

> **Scoped to your own account.** These lists apply only to your own account's redactions and are never shared with or applied to other users.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own lists. An **admin** may target another user by adding an `owner=<username>` query parameter. A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`. A deactivated user may be named as `owner`: deactivation keeps their data, and an admin reaches it as for an active user.

> Philter serves HTTPS on port 8080 with a generated self-signed certificate by default, so the `curl` examples on this page pass `-k`. See the [TLS](../../settings.md#tls) settings to supply your own certificate.

## Get the Lists

| Method | Endpoint            | Description                                          |
| ------ |---------------------|-----------------------------------------------------|
| `GET`  | `/api/redact-lists` | Get the account's always-redact and never-redact lists. |

Both lists are always present in the response; an account with no saved terms returns empty arrays. Each list's revision is returned too, for [replacing one list](#replace-one-list).

Example request:

```
curl -k -H "Authorization: Bearer <token>" https://localhost:8080/api/redact-lists
```

Example response:

```json
{
  "alwaysRedact": [
    "Project Cardinal",
    "ACME-1234"
  ],
  "neverRedact": [
    "Philterd"
  ],
  "alwaysRedactRevision": 4,
  "neverRedactRevision": 1
}
```

## Replace the Lists

| Method | Endpoint            | Description                                  |
| ------ |---------------------|----------------------------------------------|
| `POST` | `/api/redact-lists` | Replace both lists with the supplied contents. |

A `POST` **replaces both lists in full**. It is not a merge. Each field is the complete desired contents of that list. A list that is omitted or sent as an empty array is **cleared**.

> **`POST` replaces, `PUT` appends.** Use `POST` to set a list to an exact set of terms (clearing anything not included). Use [`PUT`](#append-to-the-lists) to add terms to whatever is already there without removing the existing ones.

### Request Body

A JSON object with two optional string-array fields:

* `alwaysRedact` - Terms that should always be redacted, regardless of the selected policy.
* `neverRedact` - Terms that should never be redacted, overriding all other filters.

Terms are trimmed and blank entries are dropped. Each list may contain up to **1000** terms, and each term may be up to **100** characters. Terms are matched case-insensitively. Both lists move to their next [revision](#revisions). A `POST` takes no `If-Match`; to replace one list on the condition that it has not changed, use [`PUT /api/redact-lists/{list}`](#replace-one-list). Append `:fuzzy` to an always-redact term to enable fuzzy matching (see [Always/Never Redact Lists](../../redaction/redact_lists.md#fuzzy-matching)).

### Responses

* `200 OK` - The lists were replaced.
* `400 Bad Request` - The body is malformed, a list has too many terms, or a term is too long.

Example request:

```
curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k https://localhost:8080/api/redact-lists -d '{"alwaysRedact": ["Project Cardinal", "ACME-1234"], "neverRedact": ["Philterd"]}'
```

Example response:

```json
{
  "message": "Redact lists updated. always-redact: 2, never-redact: 1."
}
```

## Append to the Lists

| Method | Endpoint            | Description                                       |
| ------ |---------------------|---------------------------------------------------|
| `PUT`  | `/api/redact-lists` | Append the supplied terms to the existing lists.  |

A `PUT` **appends** to the current lists rather than replacing them. Each field's terms are added to whatever the list already contains; the existing terms are kept. This is the difference between the two write methods:

* `POST` sets each list to *exactly* the terms you send. Terms not included are removed; an omitted or empty list is **cleared**.
* `PUT` *adds* the terms you send to the current list. Existing terms are kept; an omitted or empty list is **left unchanged**.

### Request Body

The same shape as the replace request, a JSON object with two optional string-array fields:

* `alwaysRedact` - Terms to add to the always-redact list.
* `neverRedact` - Terms to add to the never-redact list.

Terms are trimmed, blank entries are dropped, and a term that is **already present is not added again** (so appending the same term twice is a no-op). The duplicate check is an exact, case-sensitive comparison, although matching during redaction is case-insensitive: appending `project falcon` to a list holding `Project Falcon` adds a second term. After appending, each list may still contain at most **1000** terms; an append that would exceed the limit is rejected and nothing is changed. Each list given terms moves to its next [revision](#revisions); a list omitted or sent empty is not written, so its revision does not change.

### Responses

* `200 OK` - The terms were appended.
* `400 Bad Request` - The body is malformed, the resulting list would have too many terms, or a term is too long.

Example request:

```
curl -X PUT -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -k https://localhost:8080/api/redact-lists -d '{"alwaysRedact": ["Project Falcon"]}'
```

Example response:

```json
{
  "message": "Redact lists updated. Appended 1 to always-redact (now 3), 0 to never-redact (now 1)."
}
```

## Revisions

Each list has a revision, a number that every write of that list increments, whether through `POST`, `PUT`, or `PUT /api/redact-lists/{list}`. A list never written is at revision `0`. Writing one list does not change the other list's revision.

Send the revision you read in `If-Match` when you [replace one list](#replace-one-list). If the list has been written since, the write is refused with `409 Conflict` and nothing changes, so two people editing the same list cannot silently overwrite each other's terms. Read the list again, apply your change to what is there now, and retry.

## Get One List

| Method | Endpoint                   | Description                         |
| ------ |----------------------------|-------------------------------------|
| `GET`  | `/api/redact-lists/{list}` | Get one list and its revision.      |

`{list}` is `always` or `never`. Any other value returns `404 Not Found`. Requires the `lists:read` scope.

The response's `ETag` header carries the revision, quoted, such as `"4"`. Send it back as `If-Match` when replacing the list.

Example request:

```
curl -k -i -H "Authorization: Bearer <token>" https://localhost:8080/api/redact-lists/always
```

Example response:

```
ETag: "4"
```

```json
{
  "terms": [
    "Project Cardinal",
    "ACME-1234"
  ],
  "revision": 4
}
```

## Replace One List

| Method | Endpoint                   | Description                                                   |
| ------ |----------------------------|---------------------------------------------------------------|
| `PUT`  | `/api/redact-lists/{list}` | Replace one list, leaving the other as it is.                 |

`{list}` is `always` or `never`. Requires the `lists:write` scope. The list is replaced in full with the terms you send, and moves to its next [revision](#revisions). The other list is not changed.

Send the revision you read in `If-Match`, as the `ETag` gave it (`"4"`) or as a bare number (`4`). The list is replaced only if it is still at that revision, checked and written in one step. Without `If-Match`, the list is replaced whatever its revision.

### Request Body

* `terms` - The list's complete contents. An omitted or empty `terms` clears the list.

Terms are trimmed and blank entries are dropped. The list may contain up to **1000** terms, and each term may be up to **100** characters.

### Responses

* `200 OK` - The list was replaced. The body has the list and its new revision, and the `ETag` header carries the new revision.
* `400 Bad Request` - The body is malformed, the list has too many terms, a term is too long, or `If-Match` is not a single revision.
* `404 Not Found` - `{list}` is not `always` or `never`, or the `owner` does not exist or cannot be reached.
* `409 Conflict` - The list is no longer at the revision in `If-Match`. Nothing was written. `reason` is `redact_list_changed`.

Example request:

```
curl -k -X PUT -H "Content-Type: application/json" -H "Authorization: Bearer <token>" -H 'If-Match: "4"' https://localhost:8080/api/redact-lists/always -d '{"terms": ["Project Cardinal", "ACME-1234", "Project Falcon"]}'
```

Example response:

```json
{
  "terms": [
    "Project Cardinal",
    "ACME-1234",
    "Project Falcon"
  ],
  "revision": 5
}
```

Example conflict:

```json
{
  "message": "The always-redact list changed since revision 4. Read it again and retry.",
  "reason": "redact_list_changed"
}
```

A changed list takes effect for redaction within `REDACTION_CACHE_TTL_SECONDS` (default 60 seconds), as with the other writes; see [Caching](../../caching.md).
