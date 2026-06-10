# Always/Never Redact Lists API

The Always/Never Redact Lists API provides endpoints for retrieving and replacing an account's always-redact and never-redact lists. These are the terms that are unconditionally redacted, or unconditionally preserved, across all of your redaction policies and contexts. See [Always/Never Redact Lists](../../redaction/redact_lists.md) for an overview of the feature.

The lists are a per-account singleton resource: there is always exactly one (possibly empty) pair of lists per account, so there is no create or delete — only get and replace.

> **Scoped to your own account.** These lists apply only to your own account's redactions and are never shared with or applied to other users.

> **Admin cross-user access:** by default each endpoint operates on the calling user's own lists. An **admin** may target another user by adding an `owner=<username>` query parameter. A non-admin that names another user as `owner`, or an `owner` that does not exist, receives `404 Not Found`. Cross-user access is **disabled by default**; enable it with `ADMIN_CROSS_USER_ACCESS_ENABLED=true` (see [Settings](../../settings.md)). While disabled, naming another user as `owner` also returns `404 Not Found`.

> The `curl` example commands shown on this page are written assuming Philter has been enabled for SSL, and it is using a self-signed certificate. If launched from a cloud marketplace, SSL will be enabled automatically with a self-signed SSL certificate. See the [SSL/TLS](../../settings.md) settings for more information.

## Get the Lists

| Method | Endpoint            | Description                                          |
| ------ |---------------------|-----------------------------------------------------|
| `GET`  | `/api/redact-lists` | Get the account's always-redact and never-redact lists. |

Both lists are always present in the response; an account with no saved terms returns empty arrays.

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
  ]
}
```

## Replace the Lists

| Method | Endpoint            | Description                                  |
| ------ |---------------------|----------------------------------------------|
| `POST` | `/api/redact-lists` | Replace both lists with the supplied contents. |

A `POST` **replaces both lists in full** — it is not a merge. Each field is the complete desired contents of that list. A list that is omitted or sent as an empty array is **cleared**.

> **`POST` replaces, `PUT` appends.** Use `POST` to set a list to an exact set of terms (clearing anything not included). Use [`PUT`](#append-to-the-lists) to add terms to whatever is already there without removing the existing ones.

### Request Body

A JSON object with two optional string-array fields:

* `alwaysRedact` - Terms that should always be redacted, regardless of the selected policy.
* `neverRedact` - Terms that should never be redacted, overriding all other filters.

Terms are trimmed and blank entries are dropped. Each list may contain up to **1000** terms, and each term may be up to **100** characters. Terms are matched case-insensitively. Append `:fuzzy` to an always-redact term to enable fuzzy matching (see [Always/Never Redact Lists](../../redaction/redact_lists.md#fuzzy-matching)).

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

* `POST` — sets each list to *exactly* the terms you send. Terms not included are removed; an omitted or empty list is **cleared**.
* `PUT` — *adds* the terms you send to the current list. Existing terms are kept; an omitted or empty list is **left unchanged**.

### Request Body

The same shape as the replace request — a JSON object with two optional string-array fields:

* `alwaysRedact` - Terms to add to the always-redact list.
* `neverRedact` - Terms to add to the never-redact list.

Terms are trimmed, blank entries are dropped, and a term that is **already present is not added again** (so appending the same term twice is a no-op). After appending, each list may still contain at most **1000** terms; an append that would exceed the limit is rejected and nothing is changed.

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
